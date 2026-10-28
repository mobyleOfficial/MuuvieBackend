package org.mobyle.routing

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
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
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.mobyle.configureStatusPages
import org.mobyle.domain.model.CineClube
import org.mobyle.domain.model.CineClubeMovie
import org.mobyle.domain.model.CineClubeSession
import org.mobyle.domain.model.JWTClaims
import org.mobyle.domain.model.Page
import org.mobyle.domain.model.PageRequest
import org.mobyle.domain.model.UserSummary
import org.mobyle.domain.model.WatchProvider
import org.mobyle.domain.model.vocabulary.CineClubeStatus
import org.mobyle.domain.repository.AuthRepository
import org.mobyle.domain.repository.CineClubeCatalog
import org.mobyle.domain.repository.CineClubeRepository
import org.mobyle.domain.repository.PasswordHasher
import org.mobyle.domain.repository.ShelfRepository
import org.mobyle.domain.usecase.auth.ValidateToken
import org.mobyle.domain.usecase.cineclubes.CancelCineClube
import org.mobyle.domain.usecase.cineclubes.CheckCineClubeSlug
import org.mobyle.domain.usecase.cineclubes.CineClubeAccess
import org.mobyle.domain.usecase.cineclubes.CineClubeWatchlist
import org.mobyle.domain.usecase.cineclubes.CreateCineClube
import org.mobyle.domain.usecase.cineclubes.GetCineClube
import org.mobyle.domain.usecase.cineclubes.GetCineClubeParticipants
import org.mobyle.domain.usecase.cineclubes.GetCineClubeProgress
import org.mobyle.domain.usecase.cineclubes.JoinCineClube
import org.mobyle.domain.usecase.cineclubes.LeaveCineClube
import org.mobyle.domain.usecase.cineclubes.ListCineClubes
import org.mobyle.domain.usecase.cineclubes.UpdateCineClube
import org.mobyle.domain.usecase.feed.ActivityPublisher
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class CineClubesRoutingTest {

    private val repository = mockk<CineClubeRepository>(relaxed = true)
    private val shelves = mockk<ShelfRepository>(relaxed = true)
    private val catalog = mockk<CineClubeCatalog>()
    private val hasher = mockk<PasswordHasher>()
    private val activities = mockk<ActivityPublisher>(relaxed = true)
    private val authRepository = mockk<AuthRepository>()

    private val ana = UserSummary("ana", "ana", "ana.cine", "Ana", null, null)
    private val today = LocalDate(2026, 10, 5)

    private fun cineclube(status: CineClubeStatus = CineClubeStatus.ACTIVE): CineClube {
        val session = CineClubeSession(
            id = 3, position = 0, movie = CineClubeMovie(10, 100, "Rashomon", null, 1950, 88),
            startsOn = LocalDate(2026, 10, 1), endsOn = LocalDate(2026, 10, 12), debateOn = LocalDate(2026, 10, 12)
        )
        return CineClube(
            id = 1, slug = "kurosawa", title = "Kurosawa", description = null, accentColor = null, status = status,
            hasPassword = true, debateLink = null, socialLink = null, isCycle = false, startsOn = session.startsOn,
            endsOn = session.endsOn, owner = ana, sessions = listOf(session), currentSessionId = 3, totalParticipants = 1,
            createdAt = Instant.fromEpochSeconds(0)
        )
    }

    @BeforeTest
    fun setUp() {
        coEvery { authRepository.validateToken("ana-token") } returns
            Result.success(JWTClaims("ana", "ana@example.com", "ana", null, 0, Long.MAX_VALUE))
        coEvery { authRepository.validateToken("bia-token") } returns
            Result.success(JWTClaims("bia", "bia@example.com", "bia", null, 0, Long.MAX_VALUE))
        every { repository.get("kurosawa", any()) } returns cineclube()
        every { repository.participation(any(), any()) } returns null
        every { repository.list(any(), any(), any(), any()) } answers { Page.of(emptyList(), thirdArg<PageRequest>(), 0) }
        every { repository.passwordHash(1) } returns "hash"
        every { repository.participants(any(), any(), any()) } returns emptyList()
        every { repository.sessionLogs(any(), any(), any(), any()) } answers { Page.of(emptyList(), arg<PageRequest>(3), 0) }
        every { hasher.verify("pipoca", "hash") } returns true
        every { hasher.verify("errada", "hash") } returns false
        coEvery { catalog.watchProviders(100) } returns listOf(WatchProvider("Mubi"))

        startKoin {
            modules(module {
                single { ValidateToken(authRepository) }
                single { CineClubeAccess(repository) { today } }
                single { CineClubeWatchlist(repository, shelves) }
                single { ListCineClubes(repository) { today } }
                single { GetCineClube(get(), catalog) }
                single { CheckCineClubeSlug(repository) }
                single { CreateCineClube(repository, get(), shelves, hasher, get(), activities) { today } }
                single { UpdateCineClube(repository, get(), shelves, hasher) { today } }
                single { CancelCineClube(repository, get()) }
                single { JoinCineClube(repository, get(), hasher, get(), activities) }
                single { LeaveCineClube(repository, get(), activities) }
                single { GetCineClubeProgress(repository, get()) { today } }
                single { GetCineClubeParticipants(repository, get()) }
            })
        }
    }

    @AfterTest
    fun tearDown() = stopKoin()

    private fun withApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            install(ContentNegotiation) { json() }
            configureStatusPages()
            routing {
                route(API_V1_PREFIX) { getCineClubesRouting() }
            }
        }
        block()
    }

    private fun error(body: String) = Json.parseToJsonElement(body).jsonObject["error"]!!.jsonPrimitive.content

    private val validBody = """
        {"slug":"kurosawa","title":"Kurosawa","sessions":[{"movieId":10,"startsOn":"2099-10-01","endsOn":"2099-10-07"}]}
    """.trimIndent()

    @Test
    fun `the list, the detail, the progress and the participants work without login`() = withApp {
        assertEquals(HttpStatusCode.OK, client.get("$API_V1_PREFIX/cineclubes?q=kuro&open=true").status)
        verify { repository.list(null, match { it.query == "kuro" && it.open && !it.mine }, any(), any()) }

        val detail = Json.parseToJsonElement(client.get("$API_V1_PREFIX/cineclubes/kurosawa").bodyAsText()).jsonObject
        assertEquals("kurosawa", detail["slug"]!!.jsonPrimitive.content)
        assertEquals("Mubi", detail["watchProviders"]!!.jsonArray[0].jsonObject["name"]!!.jsonPrimitive.content)

        assertEquals(HttpStatusCode.OK, client.get("$API_V1_PREFIX/cineclubes/kurosawa/progress").status)
        assertEquals(HttpStatusCode.OK, client.get("$API_V1_PREFIX/cineclubes/kurosawa/participants?status=completed").status)
    }

    @Test
    fun `bad session and status parameters are a 400 and an unknown session a 404`() = withApp {
        assertEquals(HttpStatusCode.BadRequest, client.get("$API_V1_PREFIX/cineclubes/kurosawa/progress?session=x").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("$API_V1_PREFIX/cineclubes/kurosawa/participants?status=talvez").status)
        assertEquals(HttpStatusCode.NotFound, client.get("$API_V1_PREFIX/cineclubes/kurosawa/progress?session=99").status)
    }

    @Test
    fun `my CineClubes and the writes need a login`() = withApp {
        assertEquals(HttpStatusCode.Unauthorized, client.get("$API_V1_PREFIX/cineclubes?mine=true").status)
        val post = client.post("$API_V1_PREFIX/cineclubes") {
            contentType(ContentType.Application.Json)
            setBody(validBody)
        }
        assertEquals(HttpStatusCode.Unauthorized, post.status)
    }

    @Test
    fun `an unknown CineClube is a 404`() = withApp {
        every { repository.get("nada", any()) } returns null

        assertEquals(HttpStatusCode.NotFound, client.get("$API_V1_PREFIX/cineclubes/nada").status)
    }

    @Test
    fun `a taken slug is a 409 and a session without a movie a 400`() = withApp {
        every { repository.slugTaken("kurosawa") } returns true
        every { shelves.resolveMovieId(10, null, null) } returns 10
        val taken = client.post("$API_V1_PREFIX/cineclubes") {
            header("Authorization", "Bearer ana-token")
            contentType(ContentType.Application.Json)
            setBody(validBody)
        }
        assertEquals(HttpStatusCode.Conflict, taken.status)
        assertEquals("slug_taken", error(taken.bodyAsText()))

        val noMovie = client.post("$API_V1_PREFIX/cineclubes") {
            header("Authorization", "Bearer ana-token")
            contentType(ContentType.Application.Json)
            setBody(validBody.replace(""""movieId":10,""", ""))
        }
        assertEquals(HttpStatusCode.BadRequest, noMovie.status)
        assertEquals("validation_failed", error(noMovie.bodyAsText()))
    }

    @Test
    fun `joining with a wrong password is a 403 and a closed CineClube a 409`() = withApp {
        val wrong = client.post("$API_V1_PREFIX/cineclubes/kurosawa/participation") {
            header("Authorization", "Bearer bia-token")
            contentType(ContentType.Application.Json)
            setBody("""{"password":"errada"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, wrong.status)
        assertEquals("wrong_password", error(wrong.bodyAsText()))

        every { repository.get("kurosawa", any()) } returns cineclube(CineClubeStatus.FINISHED)
        val closed = client.post("$API_V1_PREFIX/cineclubes/kurosawa/participation") {
            header("Authorization", "Bearer bia-token")
            contentType(ContentType.Application.Json)
            setBody("""{"password":"pipoca"}""")
        }
        assertEquals(HttpStatusCode.Conflict, closed.status)
        assertEquals("cineclube_closed", error(closed.bodyAsText()))
    }

    @Test
    fun `only the owner cancels`() = withApp {
        val response = client.post("$API_V1_PREFIX/cineclubes/kurosawa/cancel") { header("Authorization", "Bearer bia-token") }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertEquals("not_cineclube_owner", error(response.bodyAsText()))
        verify(exactly = 0) { repository.setStatus(any(), any()) }
    }
}
