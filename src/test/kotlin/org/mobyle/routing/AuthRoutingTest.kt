package org.mobyle.routing

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.mobyle.configureStatusPages
import org.mobyle.domain.model.AuthToken
import org.mobyle.domain.model.ConflictException
import org.mobyle.domain.model.JWTClaims
import org.mobyle.domain.model.SocialProvider
import org.mobyle.domain.model.UnauthorizedException
import org.mobyle.domain.model.UnavailableException
import org.mobyle.domain.model.User
import org.mobyle.domain.repository.AuthRepository
import org.mobyle.domain.usecase.auth.CheckNicknameAvailability
import org.mobyle.domain.usecase.auth.ChooseNickname
import org.mobyle.domain.usecase.auth.LogoutUser
import org.mobyle.domain.usecase.auth.RefreshToken
import org.mobyle.domain.usecase.auth.SignInWithProvider
import org.mobyle.domain.usecase.auth.ValidateToken
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class AuthRoutingTest {

    private val authRepository = mockk<AuthRepository>()

    private fun authToken(isNewUser: Boolean, nicknameConfirmed: Boolean) = AuthToken(
        accessToken = "access",
        expiresIn = 3600,
        refreshToken = "refresh",
        user = User(
            id = "u1",
            email = "",
            username = "cosmic_ripley_42",
            createdAt = "2026-11-17T10:00:00Z",
            nicknameConfirmed = nicknameConfirmed
        ),
        isNewUser = isNewUser
    )

    @BeforeTest
    fun setUp() {
        coEvery { authRepository.validateToken("ana-token") } returns
            Result.success(JWTClaims("u1", "", "cosmic_ripley_42", null, 0, Long.MAX_VALUE))
        startKoin {
            modules(module {
                single { ValidateToken(authRepository) }
                single { SignInWithProvider(authRepository) }
                single { ChooseNickname(authRepository) }
                single { RefreshToken(authRepository) }
                single { LogoutUser(authRepository) }
                single { CheckNicknameAvailability(authRepository) }
            })
        }
    }

    @AfterTest
    fun tearDown() = stopKoin()

    private fun withApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            install(ContentNegotiation) { json() }
            configureStatusPages()
            routing { route(API_V1_PREFIX) { getAuthRouting() } }
        }
        block()
    }

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    private suspend fun ApplicationTestBuilder.social(body: String) = client.post("/api/v1/auth/social") {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    @Test
    fun `a new account answers 201 and asks for a nickname`() = withApp {
        coEvery { authRepository.signInWithProvider(SocialProvider.APPLE, "id-token", "raw") } returns
            authToken(isNewUser = true, nicknameConfirmed = false)

        val response = social("""{"provider":"apple","idToken":"id-token","nonce":"raw"}""")

        assertEquals(HttpStatusCode.Created, response.status)
        val body = json(response.bodyAsText())
        assertEquals("access", body["accessToken"]!!.jsonPrimitive.content)
        assertEquals("refresh", body["refreshToken"]!!.jsonPrimitive.content)
        assertEquals("true", body["needsNickname"]!!.jsonPrimitive.content)
        assertEquals("u1", body["profile"]!!.jsonObject["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an existing account answers 200 without asking for a nickname`() = withApp {
        coEvery { authRepository.signInWithProvider(SocialProvider.GOOGLE, "id-token", null) } returns
            authToken(isNewUser = false, nicknameConfirmed = true)

        val response = social("""{"provider":"Google","idToken":"id-token"}""")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("false", json(response.bodyAsText())["needsNickname"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an unknown provider or a blank token is a 400`() = withApp {
        assertEquals(HttpStatusCode.BadRequest, social("""{"provider":"facebook","idToken":"t"}""").status)
        assertEquals(HttpStatusCode.BadRequest, social("""{"provider":"google","idToken":" "}""").status)
        assertEquals(HttpStatusCode.BadRequest, social("""{"provider":"google"}""").status)
    }

    @Test
    fun `an invalid token is a 401 and an unconfigured provider a 503`() = withApp {
        coEvery { authRepository.signInWithProvider(SocialProvider.GOOGLE, "bad", null) } throws
            UnauthorizedException("invalid_token", "The sign-in token is invalid or expired")
        coEvery { authRepository.signInWithProvider(SocialProvider.APPLE, "t", "n") } throws
            UnavailableException("provider_not_configured", "off")

        val bad = social("""{"provider":"google","idToken":"bad"}""")
        val off = social("""{"provider":"apple","idToken":"t","nonce":"n"}""")

        assertEquals(HttpStatusCode.Unauthorized, bad.status)
        assertEquals("invalid_token", json(bad.bodyAsText())["error"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.ServiceUnavailable, off.status)
    }

    @Test
    fun `choosing a nickname needs a token and answers the trimmed nickname`() = withApp {
        coEvery { authRepository.chooseNickname("u1", " Ana ") } returns "Ana"

        val anonymous = client.put("/api/v1/auth/nickname") {
            contentType(ContentType.Application.Json)
            setBody("""{"nickname":" Ana "}""")
        }
        val ok = client.put("/api/v1/auth/nickname") {
            header("Authorization", "Bearer ana-token")
            contentType(ContentType.Application.Json)
            setBody("""{"nickname":" Ana "}""")
        }

        assertEquals(HttpStatusCode.Unauthorized, anonymous.status)
        assertEquals(HttpStatusCode.OK, ok.status)
        assertEquals("Ana", json(ok.bodyAsText())["nickname"]!!.jsonPrimitive.content)
        coVerify(exactly = 1) { authRepository.chooseNickname(any(), any()) }
    }

    @Test
    fun `a nickname in use is a 409`() = withApp {
        coEvery { authRepository.chooseNickname("u1", "joao") } throws
            ConflictException("nickname_already_exists", "This nickname is already taken")

        val response = client.put("/api/v1/auth/nickname") {
            header("Authorization", "Bearer ana-token")
            contentType(ContentType.Application.Json)
            setBody("""{"nickname":"joao"}""")
        }

        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals("nickname_already_exists", json(response.bodyAsText())["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun `password and OAuth-callback routes are gone`() = withApp {
        assertEquals(HttpStatusCode.NotFound, client.post("/api/v1/auth/login").status)
        assertEquals(HttpStatusCode.NotFound, client.post("/api/v1/auth/signup").status)
        assertEquals(HttpStatusCode.NotFound, client.post("/api/v1/auth/oauth/callback").status)
    }
}
