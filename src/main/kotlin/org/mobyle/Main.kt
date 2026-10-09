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
import org.mobyle.data.local.database.DatabaseConfig
import org.mobyle.data.local.database.GenresTable
import org.mobyle.data.local.auth.TokenBlocklistDataSource
import org.mobyle.data.remote.articles.ArticlesDataSource
import org.mobyle.data.remote.tmdb.TmdbDataSource
import org.mobyle.data.service.MovieEnrichmentService
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.koin.ktor.ext.inject
import org.mobyle.data.di.dataModule
import org.mobyle.di.appModule
import org.mobyle.routing.getActivitiesRouting
import org.mobyle.routing.getArticlesRouting
import org.mobyle.routing.getAuthRouting
import org.mobyle.routing.getCommentsRouting
import org.mobyle.routing.getFilmowRouting
import org.mobyle.routing.getMoviesRouting
import org.mobyle.routing.getPersonsRouting
import org.mobyle.routing.getProfileRouting
import org.mobyle.routing.getSocialRouting
import org.mobyle.routing.getWebSocketRouting
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
        configureStatusPages()
        configureKoin()
        configureWebSockets()
        configureRouting()
        scheduleArticleScraping()
        scheduleTokenBlocklistCleanup()
        seedGenres()
        startMovieEnrichment()
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

private fun Application.configureStatusPages() {
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.respond(
                HttpStatusCode.InternalServerError, mapOf(
                    "error" to "Internal Server Error",
                    "message" to (cause.message ?: "Unknown error")
                )
            )
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
            delay(intervalMs)
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
        getAuthRouting()
        getMoviesRouting()
        getPersonsRouting()
        getProfileRouting()
        getActivitiesRouting()
        getSocialRouting()
        getCommentsRouting()
        getArticlesRouting()
        getFilmowRouting()
        getWebSocketRouting()

        // TODO: remove — temporary endpoint to reset all data except users
        post("/admin/reset-db") {
            transaction {
                // FK children first, then parents
                exec("DELETE FROM movie_tags")
                exec("DELETE FROM movie_similars")
                exec("DELETE FROM movie_watch_providers")
                exec("DELETE FROM movie_cast")
                exec("DELETE FROM movie_genres")
                exec("DELETE FROM user_list_items")
                exec("DELETE FROM user_lists")
                exec("DELETE FROM user_movies")
                exec("DELETE FROM movie_likes")
                exec("DELETE FROM review_likes")
                exec("DELETE FROM movies")
                exec("DELETE FROM people")
                exec("DELETE FROM genres")
                exec("DELETE FROM tags")
                exec("DELETE FROM articles")
                exec("DELETE FROM user_follows")
                exec("DELETE FROM token_blocklist")
                exec("DELETE FROM refresh_tokens")
            }
            log.info("[ADMIN] Full database reset (users preserved)")
            call.respond(io.ktor.http.HttpStatusCode.OK, mapOf("status" to "reset complete — users preserved"))
        }
    }
}
