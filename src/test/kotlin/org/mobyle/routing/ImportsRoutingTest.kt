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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.mobyle.configureStatusPages
import org.mobyle.domain.model.ImportJob
import org.mobyle.domain.model.JWTClaims
import org.mobyle.domain.model.vocabulary.ImportSource
import org.mobyle.domain.model.vocabulary.ImportStatus
import org.mobyle.domain.repository.AuthRepository
import org.mobyle.domain.repository.ImportJobQueue
import org.mobyle.domain.repository.ImportRepository
import org.mobyle.domain.usecase.auth.ValidateToken
import org.mobyle.domain.usecase.imports.CancelImport
import org.mobyle.domain.usecase.imports.ConfirmImport
import org.mobyle.domain.usecase.imports.GetImport
import org.mobyle.domain.usecase.imports.GetImports
import org.mobyle.domain.usecase.imports.GetUnmatchedItems
import org.mobyle.domain.usecase.imports.ImportAccess
import org.mobyle.domain.usecase.imports.ResolveUnmatchedItem
import org.mobyle.domain.usecase.imports.StartImport
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ImportsRoutingTest {

    private val repository = mockk<ImportRepository>(relaxed = true)
    private val queue = mockk<ImportJobQueue>(relaxed = true)
    private val authRepository = mockk<AuthRepository>()

    private val job = ImportJob(
        id = 7, source = ImportSource.FILMOW, status = ImportStatus.PENDING, inputRef = "ana", processed = 0, total = 0,
        preview = null, options = null, counts = null, error = null,
        createdAt = Instant.fromEpochSeconds(0), updatedAt = Instant.fromEpochSeconds(0), finishedAt = null, userId = "ana"
    )

    @BeforeTest
    fun setUp() {
        coEvery { authRepository.validateToken("ana-token") } returns
            Result.success(JWTClaims("ana", "ana@example.com", "ana", null, 0, Long.MAX_VALUE))
        coEvery { authRepository.validateToken("bia-token") } returns
            Result.success(JWTClaims("bia", "bia@example.com", "bia", null, 0, Long.MAX_VALUE))
        every { repository.createJob("ana", ImportSource.FILMOW, "ana") } returns job
        every { repository.getJob(7) } returns job

        startKoin {
            modules(module {
                single { ValidateToken(authRepository) }
                single { ImportAccess(repository) }
                single { StartImport(repository, queue) }
                single { GetImport(get()) }
                single { GetImports(repository) }
                single { ConfirmImport(repository, get(), queue) }
                single { CancelImport(repository, get()) }
                single { GetUnmatchedItems(repository, get()) }
                single { ResolveUnmatchedItem(repository, get()) }
            })
        }
    }

    @AfterTest
    fun tearDown() {
        stopKoin()
    }

    private fun withApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            configureStatusPages()
            install(ContentNegotiation) { json() }
            routing {
                route(API_V1_PREFIX) { getImportsRouting() }
            }
        }
        block()
    }

    @Test
    fun `starting answers 202 with the job, without echoing the cookies`() = withApp {
        val response = client.post("/api/v1/imports") {
            header("Authorization", "Bearer ana-token")
            contentType(ContentType.Application.Json)
            setBody("""{"source":"filmow","username":"ana","cookies":"session=secret"}""")
        }

        assertEquals(HttpStatusCode.Accepted, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("pending", body["status"]!!.jsonPrimitive.content)
        assertEquals(false, response.bodyAsText().contains("secret"))
        verify { queue.read(job, "session=secret", false) }
    }

    @Test
    fun `routes need a token and hide other users' jobs`() = withApp {
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/imports/7").status)

        val response = client.get("/api/v1/imports/7") { header("Authorization", "Bearer bia-token") }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `a second active import conflicts`() = withApp {
        every { repository.createJob("ana", ImportSource.FILMOW, "ana") } returns null

        val response = client.post("/api/v1/imports") {
            header("Authorization", "Bearer ana-token")
            contentType(ContentType.Application.Json)
            setBody("""{"source":"filmow","username":"ana"}""")
        }

        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals("import_in_progress", Json.parseToJsonElement(response.bodyAsText()).jsonObject["error"]!!.jsonPrimitive.content)
    }
}
