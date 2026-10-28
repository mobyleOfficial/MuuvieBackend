package org.mobyle.routing

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.serialization.Serializable
import org.mobyle.data.remote.auth.authenticateJWT
import org.mobyle.data.remote.auth.tryAuthenticateJWT
import org.mobyle.di.injection
import org.mobyle.domain.model.ShelfMovieFilter
import org.mobyle.domain.model.ShelfTransferMode
import org.mobyle.domain.model.ValidationException
import org.mobyle.domain.model.vocabulary.Rating
import org.mobyle.domain.usecase.auth.ValidateToken
import org.mobyle.domain.usecase.shelves.AddMovieToShelves
import org.mobyle.domain.usecase.shelves.CreateShelf
import org.mobyle.domain.usecase.shelves.DeleteShelf
import org.mobyle.domain.usecase.shelves.GetMovieShelves
import org.mobyle.domain.usecase.shelves.GetPublicShelvesWithMovie
import org.mobyle.domain.usecase.shelves.GetSavedShelves
import org.mobyle.domain.usecase.shelves.GetShelf
import org.mobyle.domain.usecase.shelves.GetShelfMovies
import org.mobyle.domain.usecase.shelves.GetUserShelves
import org.mobyle.domain.usecase.shelves.RemoveMovieFromShelf
import org.mobyle.domain.usecase.shelves.ReorderShelves
import org.mobyle.domain.usecase.shelves.SaveShelf
import org.mobyle.domain.usecase.shelves.SearchShelves
import org.mobyle.domain.usecase.shelves.SortShelf
import org.mobyle.domain.usecase.shelves.TransferShelfMovies
import org.mobyle.domain.usecase.shelves.UnsaveShelf
import org.mobyle.domain.usecase.shelves.UpdateShelf

@Serializable
data class ShelfRequest(
    val name: String,
    val description: String? = null,
    val color: String? = null,
    val visibility: String? = null
)

/** A movie by database id, or by TMDB id (with its title) when it may not be saved yet. */
@Serializable
data class ShelfMovieRequest(val movieId: Long? = null, val tmdbId: Int? = null, val title: String? = null)

@Serializable
data class MovieShelvesRequest(val shelfIds: List<Long>, val title: String? = null)

@Serializable
data class TransferShelfMoviesRequest(
    val targetShelfId: Long,
    val mode: String,
    val movieIds: List<Long> = emptyList()
)

@Serializable
data class SortShelfRequest(
    val sortMode: String? = null,
    val applyOnce: Boolean = false,
    val movieIds: List<Long>? = null
)

@Serializable
data class ReorderShelvesRequest(val shelfIds: List<Long> = emptyList(), val pinnedIds: List<Long>? = null)

/** Shelves of the v1 API (B1). Only mounted under /api/v1; the legacy /profile/shelves routes stay until B11. */
fun Route.getShelvesRouting() {
    val validateToken by injection<ValidateToken>()
    val getUserShelves by injection<GetUserShelves>()
    val searchShelves by injection<SearchShelves>()
    val getShelf by injection<GetShelf>()
    val createShelf by injection<CreateShelf>()
    val updateShelf by injection<UpdateShelf>()
    val deleteShelf by injection<DeleteShelf>()
    val getShelfMovies by injection<GetShelfMovies>()
    val addMovieToShelves by injection<AddMovieToShelves>()
    val removeMovieFromShelf by injection<RemoveMovieFromShelf>()
    val transferShelfMovies by injection<TransferShelfMovies>()
    val sortShelf by injection<SortShelf>()
    val reorderShelves by injection<ReorderShelves>()
    val getMovieShelves by injection<GetMovieShelves>()
    val getPublicShelvesWithMovie by injection<GetPublicShelvesWithMovie>()
    val saveShelf by injection<SaveShelf>()
    val unsaveShelf by injection<UnsaveShelf>()
    val getSavedShelves by injection<GetSavedShelves>()

    get("/users/{userId}/shelves") {
        val userId = call.parameters["userId"]!!
        val viewerId = if (userId == "me") {
            call.authenticateJWT(validateToken)?.claims?.userId ?: return@get
        } else {
            call.tryAuthenticateJWT(validateToken)?.claims?.userId
        }
        val page = call.pageRequest() ?: return@get
        val profile = call.request.queryParameters["profile"] == "true"
        call.respond(getUserShelves(viewerId, if (userId == "me") viewerId!! else userId, page, profile))
    }

    put("/users/me/shelves/order") {
        val principal = call.authenticateJWT(validateToken) ?: return@put
        val request = call.receive<ReorderShelvesRequest>()
        reorderShelves(principal.claims.userId, request.shelfIds, request.pinnedIds)
        call.respond(HttpStatusCode.NoContent)
    }

    // "Salvas" (B14.4): shelves of others I saved, the last saved first.
    get("/users/me/shelves/saved") {
        val principal = call.authenticateJWT(validateToken) ?: return@get
        val page = call.pageRequest() ?: return@get
        call.respond(getSavedShelves(principal.claims.userId, page))
    }

    post("/shelves") {
        val principal = call.authenticateJWT(validateToken) ?: return@post
        val request = call.receive<ShelfRequest>()
        val shelf = createShelf(principal.claims.userId, request.name, request.description, request.color, request.visibility)
        call.respond(HttpStatusCode.Created, shelf)
    }

    get("/shelves/search") {
        val principal = call.authenticateJWT(validateToken) ?: return@get
        val page = call.pageRequest() ?: return@get
        call.respond(searchShelves(principal.claims.userId, call.parameters["q"].orEmpty(), page))
    }

    get("/shelves/{id}") {
        val shelfId = call.longParameter("id") ?: return@get
        val viewerId = call.tryAuthenticateJWT(validateToken)?.claims?.userId
        call.respond(getShelf(viewerId, shelfId))
    }

    put("/shelves/{id}") {
        val principal = call.authenticateJWT(validateToken) ?: return@put
        val shelfId = call.longParameter("id") ?: return@put
        val request = call.receive<ShelfRequest>()
        call.respond(
            updateShelf(principal.claims.userId, shelfId, request.name, request.description, request.color, request.visibility)
        )
    }

    delete("/shelves/{id}") {
        val principal = call.authenticateJWT(validateToken) ?: return@delete
        val shelfId = call.longParameter("id") ?: return@delete
        deleteShelf(principal.claims.userId, shelfId)
        call.respond(HttpStatusCode.NoContent)
    }

    post("/shelves/{id}/save") {
        val principal = call.authenticateJWT(validateToken) ?: return@post
        val shelfId = call.longParameter("id") ?: return@post
        saveShelf(principal.claims.userId, shelfId)
        call.respond(HttpStatusCode.NoContent)
    }

    delete("/shelves/{id}/save") {
        val principal = call.authenticateJWT(validateToken) ?: return@delete
        val shelfId = call.longParameter("id") ?: return@delete
        unsaveShelf(principal.claims.userId, shelfId)
        call.respond(HttpStatusCode.NoContent)
    }

    get("/shelves/{id}/movies") {
        val shelfId = call.longParameter("id") ?: return@get
        val viewerId = call.tryAuthenticateJWT(validateToken)?.claims?.userId
        val page = call.pageRequest() ?: return@get
        call.respond(getShelfMovies(viewerId, shelfId, call.shelfMovieFilter(), page))
    }

    post("/shelves/{id}/movies") {
        val principal = call.authenticateJWT(validateToken) ?: return@post
        val shelfId = call.longParameter("id") ?: return@post
        val request = call.receive<ShelfMovieRequest>()
        addMovieToShelves(principal.claims.userId, listOf(shelfId), request.movieId, request.tmdbId, request.title)
        call.respond(HttpStatusCode.Created)
    }

    delete("/shelves/{id}/movies/{movieId}") {
        val principal = call.authenticateJWT(validateToken) ?: return@delete
        val shelfId = call.longParameter("id") ?: return@delete
        val movieId = call.longParameter("movieId") ?: return@delete
        removeMovieFromShelf(principal.claims.userId, shelfId, movieId)
        call.respond(HttpStatusCode.NoContent)
    }

    post("/shelves/{id}/movies/transfer") {
        val principal = call.authenticateJWT(validateToken) ?: return@post
        val shelfId = call.longParameter("id") ?: return@post
        val request = call.receive<TransferShelfMoviesRequest>()
        val mode = when (request.mode) {
            "move" -> ShelfTransferMode.MOVE
            "copy" -> ShelfTransferMode.COPY
            else -> throw ValidationException("mode", "mode must be move or copy")
        }
        transferShelfMovies(principal.claims.userId, shelfId, request.targetShelfId, mode, request.movieIds)
        call.respond(HttpStatusCode.NoContent)
    }

    put("/shelves/{id}/order") {
        val principal = call.authenticateJWT(validateToken) ?: return@put
        val shelfId = call.longParameter("id") ?: return@put
        val request = call.receive<SortShelfRequest>()
        sortShelf(principal.claims.userId, shelfId, request.sortMode, request.applyOnce, request.movieIds)
        call.respond(HttpStatusCode.NoContent)
    }

    get("/movies/{id}/shelves") {
        val principal = call.authenticateJWT(validateToken) ?: return@get
        val movieId = call.longParameter("id") ?: return@get
        call.respond(getMovieShelves(principal.claims.userId, movieId = movieId, tmdbId = null))
    }

    // Public: custom public shelves of anyone that hold the movie (B13.2).
    get("/movies/{id}/shelves/public") {
        val movieId = call.longParameter("id") ?: return@get
        val page = call.pageRequest() ?: return@get
        call.respond(getPublicShelvesWithMovie(movieId, page))
    }

    get("/movies/tmdb/{tmdbId}/shelves") {
        val principal = call.authenticateJWT(validateToken) ?: return@get
        val tmdbId = call.longParameter("tmdbId")?.toInt() ?: return@get
        call.respond(getMovieShelves(principal.claims.userId, movieId = null, tmdbId = tmdbId))
    }

    post("/movies/{id}/shelves") {
        val principal = call.authenticateJWT(validateToken) ?: return@post
        val movieId = call.longParameter("id") ?: return@post
        val request = call.receive<MovieShelvesRequest>()
        addMovieToShelves(principal.claims.userId, request.shelfIds, movieId, null, null)
        call.respond(getMovieShelves(principal.claims.userId, movieId = movieId, tmdbId = null))
    }

    post("/movies/tmdb/{tmdbId}/shelves") {
        val principal = call.authenticateJWT(validateToken) ?: return@post
        val tmdbId = call.longParameter("tmdbId")?.toInt() ?: return@post
        val request = call.receive<MovieShelvesRequest>()
        val movieId = addMovieToShelves(principal.claims.userId, request.shelfIds, null, tmdbId, request.title)
        call.respond(getMovieShelves(principal.claims.userId, movieId = movieId, tmdbId = null))
    }
}

private fun ApplicationCall.shelfMovieFilter(): ShelfMovieFilter {
    fun <T> parse(name: String, convert: (String) -> T?): T? =
        parameters[name]?.let { convert(it) ?: throw ValidationException(name, "Invalid $name") }

    return ShelfMovieFilter(
        watched = parse("watched") { it.toBooleanStrictOrNull() },
        genreTmdbId = parse("genre") { it.toIntOrNull() },
        decade = parse("decade") { value -> value.toIntOrNull()?.takeIf { it % 10 == 0 } },
        rating = parse("rating") { Rating.fromWireValue(it) }
    )
}
