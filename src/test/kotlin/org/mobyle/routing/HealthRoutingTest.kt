package org.mobyle.routing

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HealthRoutingTest {

    private fun withApp(
        database: Boolean = true,
        tmdb: Boolean = true,
        token: String? = null,
        block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit
    ) = testApplication {
        application {
            install(ContentNegotiation) { json() }
            configureObservability()
            routing { getHealthRouting(tmdbConfigured = { tmdb }, databaseUp = { database }, metricsToken = { token }) }
        }
        block()
    }

    @Test
    fun `live answers while the process runs and ready checks the database and TMDB`() = withApp {
        assertEquals(HttpStatusCode.OK, client.get("/health/live").status)
        val ready = client.get("/health/ready")
        assertEquals(HttpStatusCode.OK, ready.status)
        assertTrue(ready.bodyAsText().contains("\"database\":\"up\""))
    }

    @Test
    fun `ready is a 503 when the database is down`() = withApp(database = false) {
        val ready = client.get("/health/ready")

        assertEquals(HttpStatusCode.ServiceUnavailable, ready.status)
        assertTrue(ready.bodyAsText().contains("\"database\":\"down\""))
    }

    @Test
    fun `every answer carries a request id, the one sent or a new one`() = withApp {
        assertEquals("abc-123", client.get("/health/live") { header(HttpHeaders.XRequestId, "abc-123") }.headers[HttpHeaders.XRequestId])
        assertNotNull(client.get("/health/live").headers[HttpHeaders.XRequestId])
    }

    @Test
    fun `metrics need the token`() = withApp(token = "segredo") {
        assertEquals(HttpStatusCode.NotFound, client.get("/metrics").status)
        val metrics = client.get("/metrics") { header(HttpHeaders.Authorization, "Bearer segredo") }
        assertEquals(HttpStatusCode.OK, metrics.status)
    }
}
