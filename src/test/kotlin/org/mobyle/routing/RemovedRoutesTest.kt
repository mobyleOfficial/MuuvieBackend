package org.mobyle.routing

import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets
import kotlin.test.Test
import kotlin.test.assertEquals

class RemovedRoutesTest {

    @Test
    fun `admin reset-db and the unprefixed routes no longer exist`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            install(WebSockets)
            routing {
                route(API_V1_PREFIX) { apiRoutes() }
            }
        }

        assertEquals(HttpStatusCode.NotFound, client.post("/admin/reset-db").status)
        assertEquals(HttpStatusCode.NotFound, client.post("/api/v1/admin/reset-db").status)
        assertEquals(HttpStatusCode.NotFound, client.post("/auth/login").status)
    }
}
