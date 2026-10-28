package org.mobyle.routing

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import org.mobyle.data.remote.auth.ErrorResponse
import org.mobyle.domain.model.PageRequest

const val API_V1_PREFIX = "/api/v1"

/** Every HTTP route of the API, mounted under [API_V1_PREFIX]. */
fun Route.apiRoutes() {
    getAuthRouting()
    getMoviesRouting()
    getPersonsRouting()
    getProfileRouting()
    getSocialRouting()
    getShelvesRouting()
    getLocadorasRouting()
    getWatchRouting()
    getImportsRouting()
    getUsersRouting()
    getDiscoverRouting()
    getFeedRouting()
    getMessagesRouting()
    getMaratonasRouting()
    getCineClubesRouting()
    getStatsRouting()
    getGamificationRouting()
    getGamesRouting()
    getCommentsRouting()
    getArticlesRouting()
    getWebSocketRouting()
}

/** Parses a numeric path parameter; responds 400 and returns null when it is not a positive number. */
suspend fun ApplicationCall.longParameter(name: String): Long? {
    val value = parameters[name]?.toLongOrNull()?.takeIf { it > 0 && (name != "tmdbId" || it <= Int.MAX_VALUE) }
    if (value == null) {
        respond(HttpStatusCode.BadRequest, ErrorResponse("validation_failed", "Invalid $name"))
    }
    return value
}

/** Parses `page` and `limit`; responds 400 and returns null when they are invalid. */
suspend fun ApplicationCall.pageRequest(): PageRequest? {
    val page = parameters["page"]?.let { it.toIntOrNull() ?: 0 } ?: 1
    val limit = parameters["limit"]?.let { it.toIntOrNull() ?: 0 } ?: PageRequest.DEFAULT_LIMIT
    return runCatching { PageRequest(page, limit) }.getOrElse {
        respond(
            HttpStatusCode.BadRequest,
            ErrorResponse("invalid_pagination", "page must be >= 1 and limit between 1 and ${PageRequest.MAX_LIMIT}")
        )
        null
    }
}
