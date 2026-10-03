package org.mobyle.routing

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import org.mobyle.di.injection
import org.mobyle.data.remote.auth.authenticateJWT
import org.mobyle.data.remote.filmow.FilmowScrapeRequest
import org.mobyle.data.local.user.UserDatabaseDataSource
import org.mobyle.domain.usecase.auth.ValidateToken
import org.mobyle.domain.usecase.filmow.ImportFilmowData
import org.mobyle.domain.usecase.filmow.ScrapeFilmowProfile
import org.mobyle.data.service.ScrapeStatusManager
import org.mobyle.data.service.WebSocketManager
import org.mobyle.data.service.WsMessage
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("FilmowRouting")

fun Route.getFilmowRouting() {
    val scrapeFilmowProfile by injection<ScrapeFilmowProfile>()
    val importFilmowData by injection<ImportFilmowData>()
    val validateToken by injection<ValidateToken>()
    val userDatabaseDataSource by injection<UserDatabaseDataSource>()
    val scrapeStatusManager by injection<ScrapeStatusManager>()
    val webSocketManager by injection<WebSocketManager>()

    post("/filmow/scrape") {
        val principal = call.authenticateJWT(validateToken) ?: return@post
        val userId = principal.claims.userId
        val email = principal.claims.email

        val user = userDatabaseDataSource.findByEmail(email)
        if (user == null) {
            call.respond(
                HttpStatusCode.NotFound,
                mapOf("error" to "User not found")
            )
            return@post
        }

        val request = try {
            call.receive<FilmowScrapeRequest>()
        } catch (e: Exception) {
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Invalid request body. Expected: { cookies: string, username: string }")
            )
            return@post
        }

        if (request.username.isBlank()) {
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "'username' is required")
            )
            return@post
        }

        try {
            scrapeStatusManager.markScraping(user.id)
            webSocketManager.send(user.id, WsMessage(type = "scrape_started"))

            val profile = scrapeFilmowProfile(request.cookies, request.username)

            val importedProfile = try {
                importFilmowData(user.id, profile)
            } catch (e: Exception) {
                log.error("[FILMOW] Import failed for user $userId: ${e.message}", e)
                profile
            }

            scrapeStatusManager.clearScraping(user.id)
            val recentlyWatchedJson = Json.encodeToString(importedProfile.recentlyWatched)
            webSocketManager.send(user.id, WsMessage(
                type = "scrape_finished",
                payload = mapOf(
                    "watched" to importedProfile.watched.size.toString(),
                    "watchlist" to importedProfile.watchlist.size.toString(),
                    "favorites" to importedProfile.favorites.size.toString(),
                    "lists" to importedProfile.lists.size.toString(),
                    "recentlyWatched" to recentlyWatchedJson
                )
            ))

            call.respond(importedProfile)
        } catch (e: Exception) {
            scrapeStatusManager.clearScraping(user.id)
            webSocketManager.send(user.id, WsMessage(
                type = "scrape_failed",
                payload = mapOf("error" to (e.message ?: "Unknown error"))
            ))
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf(
                    "error" to "Failed to scrape Filmow profile",
                    "message" to (e.message ?: "Unknown error")
                )
            )
        }
    }
}
