package org.mobyle

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import org.mobyle.data.local.database.DatabaseConfig
import org.mobyle.domain.model.ApiError
import org.mobyle.domain.model.ConflictException
import org.mobyle.domain.model.DomainException
import org.mobyle.domain.model.ForbiddenException
import org.mobyle.domain.model.NotFoundException
import org.mobyle.domain.model.TooManyRequestsException
import org.mobyle.domain.model.UnavailableException
import org.mobyle.domain.model.ValidationException
import org.mobyle.data.local.database.GenresTable
import org.mobyle.data.local.auth.TokenBlocklistDataSource
import org.mobyle.data.remote.articles.ArticlesDataSource
import org.mobyle.data.service.ArticleMovieLinker
import org.mobyle.data.remote.tmdb.TmdbDataSource
import org.mobyle.data.service.MovieEnrichmentService
import org.mobyle.domain.repository.ImportRepository
import org.mobyle.domain.usecase.maratonas.AdvanceMaratonas
import org.mobyle.domain.usecase.cineclubes.AdvanceCineClubes
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.koin.ktor.ext.inject
import org.mobyle.data.di.dataModule
import org.mobyle.di.appModule
import org.mobyle.routing.API_V1_PREFIX
import org.mobyle.routing.apiRoutes
import org.mobyle.routing.configureObservability
import org.mobyle.routing.getHealthRouting
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("Application")

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    logTmdbEnv()
    DatabaseConfig.init()

    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        configureCors()
        configureObservability()
        configureStatusPages()
        configureKoin()
        configureWebSockets()
        configureRouting()
        scheduleArticleScraping()
        scheduleTokenBlocklistCleanup()
        seedGenres()
        startMovieEnrichment()
        scheduleMaratonas()
        scheduleCineClubes()
        failInterruptedImports()
    }.start(wait = true)
}

private fun Application.configureWebSockets() {
    install(WebSockets) {
        pingPeriod = 30.seconds
        timeout = 15.seconds
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }
}

private fun Application.configureCors() {
    install(CORS) {
        allowMethod(io.ktor.http.HttpMethod.Get)
        allowMethod(io.ktor.http.HttpMethod.Post)
        allowMethod(io.ktor.http.HttpMethod.Put)
        allowMethod(io.ktor.http.HttpMethod.Delete)
        allowMethod(io.ktor.http.HttpMethod.Options)
        allowHeader(io.ktor.http.HttpHeaders.ContentType)
        allowHeader(io.ktor.http.HttpHeaders.Authorization)
        // Local dev
        allowHost("localhost:5173", schemes = listOf("http"))
        allowHost("127.0.0.1:5173", schemes = listOf("http"))
        // Extra origins from env
        System.getenv("CORS_ORIGINS")?.split(",")?.forEach { origin ->
            val trimmed = origin.trim().takeIf { it.isNotBlank() } ?: return@forEach
            val host = trimmed.removePrefix("http://").removePrefix("https://")
            val schemes = if (trimmed.startsWith("https")) listOf("https") else listOf("http", "https")
            allowHost(host, schemes = schemes)
        }
    }
}

private fun logTmdbEnv() {
    val apiKey = System.getenv("TMDB_API_KEY")
    when {
        apiKey.isNullOrBlank() -> log.warn("TMDB_API_KEY is not set - movie API routes will fail with 500")
        else -> log.info("TMDB API key configured")
    }
}

/** Maps errors to the envelope of the v1 contract. Unexpected errors are logged, never echoed. */
fun Application.configureStatusPages() {
    install(StatusPages) {
        exception<DomainException> { call, cause ->
            val status = when (cause) {
                is NotFoundException -> HttpStatusCode.NotFound
                is ForbiddenException -> HttpStatusCode.Forbidden
                is ValidationException -> HttpStatusCode.BadRequest
                is ConflictException -> HttpStatusCode.Conflict
                is UnavailableException -> HttpStatusCode.ServiceUnavailable
                is TooManyRequestsException -> HttpStatusCode.TooManyRequests
            }
            val details = (cause as? ValidationException)?.let { mapOf("field" to it.field) }
            call.respond(status, ApiError(cause.code, cause.message.orEmpty(), details))
        }
        exception<BadRequestException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ApiError("validation_failed", "Malformed request body"))
        }
        exception<Throwable> { call, cause ->
            call.application.log.error("Unhandled error on ${call.request.httpMethod.value} ${call.request.path()}", cause)
            call.respond(HttpStatusCode.InternalServerError, ApiError("internal_error", "Unexpected error"))
        }
    }
}

fun Application.configureKoin() {
    install(Koin) {
        slf4jLogger()
        modules(dataModule, appModule)
    }
}

private fun Application.scheduleArticleScraping() {
    val articlesDataSource by inject<ArticlesDataSource>()
    val articleMovieLinker by inject<ArticleMovieLinker>()
    val intervalMs = System.getenv("SCRAPE_INTERVAL_HOURS")?.toLongOrNull()?.times(3_600_000) ?: 10_800_000L // default 3h

    CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
        while (isActive) {
            try {
                log.info("Starting scheduled article scraping...")
                articlesDataSource.scrapeAndStore()
                log.info("Article scraping completed successfully")
            } catch (e: Exception) {
                log.error("Article scraping failed", e)
            }
            // Also picks up articles a previous run could not link (e.g. TMDB was down).
            try {
                articleMovieLinker.linkPending()
            } catch (e: Exception) {
                log.error("Linking article movies failed", e)
            }
            delay(intervalMs)
        }
    }
}

/** Starts and finishes maratonas along their period (B7), on boot and then every hour. */
private fun Application.scheduleMaratonas() {
    val advanceMaratonas by inject<AdvanceMaratonas>()

    CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
        while (isActive) {
            try {
                val (started, finished) = advanceMaratonas()
                if (started + finished > 0) log.info("[MARATONA] $started started, $finished finished")
            } catch (e: Exception) {
                log.error("[MARATONA] Scheduler failed", e)
            }
            delay(60 * 60 * 1000L)
        }
    }
}

/** Starts, advances and finishes CineClubes and sends the debate pushes (B8), on boot and then every hour. */
private fun Application.scheduleCineClubes() {
    val advanceCineClubes by inject<AdvanceCineClubes>()

    CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
        while (isActive) {
            try {
                val run = advanceCineClubes()
                if (run.started + run.finished + run.sessionsOpened + run.debatesNotified > 0) {
                    log.info(
                        "[CINECLUBE] ${run.started} started, ${run.finished} finished, " +
                            "${run.sessionsOpened} sessions opened, ${run.debatesNotified} debates notified"
                    )
                }
            } catch (e: Exception) {
                log.error("[CINECLUBE] Scheduler failed", e)
            }
            delay(60 * 60 * 1000L)
        }
    }
}

private fun Application.scheduleTokenBlocklistCleanup() {
    val tokenBlocklistDataSource by inject<TokenBlocklistDataSource>()
    val intervalMs = 15 * 60 * 1000L // 15 minutes

    CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
        while (isActive) {
            delay(intervalMs)
            try {
                tokenBlocklistDataSource.cleanup()
                log.debug("Token blocklist cleanup completed")
            } catch (e: Exception) {
                log.error("Token blocklist cleanup failed", e)
            }
        }
    }
}

private fun Application.seedGenres() {
    val tmdbDataSource by inject<TmdbDataSource>()
    CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
        try {
            val count = transaction { GenresTable.selectAll().count() }
            if (count > 0L) return@launch

            log.info("Seeding genres from TMDB...")
            val genres = tmdbDataSource.getGenres().genres
            transaction {
                for (genre in genres) {
                    GenresTable.insert {
                        it[tmdbId] = genre.id
                        it[name] = genre.name ?: ""
                    }
                }
            }
            log.info("Seeded ${genres.size} genres")
        } catch (e: Exception) {
            log.error("Genre seeding failed: ${e.message}")
        }
    }
}

private fun Application.startMovieEnrichment() {
    val enrichmentService by inject<MovieEnrichmentService>()
    enrichmentService.startBackgroundLoop(intervalMinutes = 5)
}

/** Jobs a restart cut while reading or importing are failed, so the user can start again. */
private fun Application.failInterruptedImports() {
    val importRepository by inject<ImportRepository>()
    val failed = importRepository.failInterrupted()
    if (failed > 0) log.warn("Failed $failed import job(s) interrupted by the restart")
}

fun Application.configureRouting() {
    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
            isLenient = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        })
    }

    routing {
        getHealthRouting()
        route(API_V1_PREFIX) { apiRoutes() }
    }
}
