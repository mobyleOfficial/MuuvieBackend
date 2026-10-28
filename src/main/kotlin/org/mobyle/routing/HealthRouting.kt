package org.mobyle.routing

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.metrics.micrometer.MicrometerMetrics
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.event.Level
import java.util.UUID

@Serializable
data class HealthResponse(val status: String, val checks: Map<String, String> = emptyMap())

/** Prometheus registry of the process; /metrics serves it when METRICS_TOKEN is set. */
val metricsRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

/**
 * Request ids (the `X-Request-Id` the client sent, or a new one, echoed back and written in
 * every log line), a log line per API request and the HTTP metrics of Micrometer (B11.2).
 */
fun Application.configureObservability() {
    install(CallId) {
        retrieveFromHeader(HttpHeaders.XRequestId)
        generate { UUID.randomUUID().toString() }
        verify { it.isNotBlank() && it.length <= 100 }
        replyToHeader(HttpHeaders.XRequestId)
    }
    install(CallLogging) {
        level = Level.INFO
        callIdMdc("call-id")
        filter { call -> call.request.path().startsWith(API_V1_PREFIX) }
    }
    install(MicrometerMetrics) {
        registry = metricsRegistry
    }
}

/**
 * Health checks for the orchestrator (B11.2): `/health/live` answers while the process runs;
 * `/health/ready` also checks the database and that the TMDB key is set, and answers 503
 * when one fails. `/metrics` is the Prometheus scrape, behind the METRICS_TOKEN bearer.
 */
fun Route.getHealthRouting(
    tmdbConfigured: () -> Boolean = { !System.getenv("TMDB_API_KEY").isNullOrBlank() },
    databaseUp: () -> Boolean = { runCatching { transaction { exec("SELECT 1") { it.next() } } }.isSuccess },
    metricsToken: () -> String? = { System.getenv("METRICS_TOKEN")?.takeIf { it.isNotBlank() } }
) {
    get("/health/live") {
        call.respond(HealthResponse("up"))
    }

    get("/health/ready") {
        val checks = mapOf(
            "database" to if (databaseUp()) "up" else "down",
            "tmdb" to if (tmdbConfigured()) "configured" else "missing"
        )
        val ready = checks["database"] == "up" && checks["tmdb"] == "configured"
        call.respond(
            if (ready) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
            HealthResponse(if (ready) "up" else "down", checks)
        )
    }

    get("/metrics") {
        val token = metricsToken()
        if (token == null || call.request.headers[HttpHeaders.Authorization] != "Bearer $token") {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }
        call.respondText(metricsRegistry.scrape(), ContentType.parse("text/plain; version=0.0.4"))
    }
}
