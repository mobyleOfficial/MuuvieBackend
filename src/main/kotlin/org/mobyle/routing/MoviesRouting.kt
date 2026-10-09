package org.mobyle.routing

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import org.mobyle.data.remote.auth.authenticateJWT
import org.mobyle.data.remote.auth.tryAuthenticateJWT
import org.mobyle.di.injection
import org.mobyle.domain.usecase.auth.ValidateToken
import org.mobyle.domain.usecase.movies.*

@Serializable
data class CreateListRequest(val name: String, val description: String? = null, val movieIds: List<Long> = emptyList())

@Serializable
data class AddMovieRequest(val movieId: Long)

@Serializable
data class SetStatusRequest(val status: String)

@Serializable
data class RateMovieRequest(val rating: Float)

fun Route.getMoviesRouting() {
    val validateToken by injection<ValidateToken>()
    val likeMovie by injection<LikeMovie>()
    val unlikeMovie by injection<UnlikeMovie>()
    val getTrendingMovies by injection<GetTrendingMovies>()

    val searchMovies by injection<SearchMovies>()
    val discoverMovies by injection<DiscoverMovies>()
    val getGenres by injection<GetGenres>()
    val getCountries by injection<GetCountries>()
    val getLanguages by injection<GetLanguages>()
    val getMovieReviews by injection<GetMovieReviews>()
    val getUserFavoriteMovies by injection<GetUserFavoriteMovies>()
    val getUserWatchList by injection<GetUserWatchList>()
    val getUserWatchedMovies by injection<GetUserWatchedMovies>()
    val getMovieLists by injection<GetMovieLists>()
    val getUserMovieLists by injection<GetUserMovieLists>()
    val getMovieListDetail by injection<GetMovieListDetail>()
    val getFeaturedLists by injection<GetFeaturedLists>()
    val getRecentMovies by injection<GetRecentMovies>()
    val lookupMovieDetail by injection<LookupMovieDetail>()
    val createMovieList by injection<CreateMovieList>()
    val deleteMovieList by injection<DeleteMovieList>()
    val addMovieToList by injection<AddMovieToList>()
    val removeMovieFromList by injection<RemoveMovieFromList>()
    val setMovieStatus by injection<SetMovieStatus>()
    val rateMovie by injection<RateMovie>()

    get("/movies") {
        val id = call.parameters["id"]?.toLongOrNull()
        val tmdbId = call.parameters["tmdbId"]?.toIntOrNull()
        val filmowId = call.parameters["filmowId"]
        val userId = call.tryAuthenticateJWT(validateToken)?.claims?.userId

        val detail = lookupMovieDetail(id, tmdbId, filmowId, userId)
        if (detail == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Movie not found"))
        } else {
            call.respond(detail)
        }
    }

    get("/movies/trending") {
        val page = call.parameters["page"]?.toIntOrNull() ?: 1
        call.respond(getTrendingMovies(page))
    }

    get("/movies/search") {
        val query = call.parameters["query"]?.trim()
        if (query.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, "Query parameter is required")
            return@get
        }
        val page = call.parameters["page"]?.toIntOrNull() ?: 1
        call.respond(searchMovies(query, page))
    }

    get("/movies/discover") {
        val page = call.parameters["page"]?.toIntOrNull() ?: 1
        val year = call.parameters["year"]?.toIntOrNull()
        val releaseDateGte = call.parameters["release_date_gte"]
        val releaseDateLte = call.parameters["release_date_lte"]
        val sortBy = call.parameters["sort_by"]
        val genres = call.parameters["with_genres"]
        val language = call.parameters["with_original_language"]
        val country = call.parameters["with_origin_country"]
        val voteCountGte = call.parameters["vote_count_gte"]?.toIntOrNull()

        call.respond(
            discoverMovies(
                page = page,
                year = year,
                releaseDateGte = releaseDateGte,
                releaseDateLte = releaseDateLte,
                sortBy = sortBy,
                genres = genres,
                language = language,
                country = country,
                voteCountGte = voteCountGte
            )
        )
    }

    get("/movies/genres") {
        call.respond(getGenres())
    }

    get("/movies/countries") {
        call.respond(getCountries())
    }

    get("/movies/languages") {
        call.respond(getLanguages())
    }

    get("/movies/{id}") {
        val tmdbId = call.parameters["id"]?.toIntOrNull()
        if (tmdbId == null) {
            call.respond(HttpStatusCode.BadRequest, "Invalid movie ID")
            return@get
        }
        val userId = call.tryAuthenticateJWT(validateToken)?.claims?.userId
        val detail = lookupMovieDetail(null, tmdbId, null, userId)
        if (detail == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Movie not found"))
        } else {
            call.respond(detail)
        }
    }

    post("/movies/{id}/like") {
        val principal = call.authenticateJWT(validateToken) ?: return@post
        val movieId = call.parameters["id"]?.toLongOrNull()
        if (movieId == null) {
            call.respond(HttpStatusCode.BadRequest, "Invalid movie ID")
            return@post
        }
        likeMovie(principal.claims.userId, movieId)
        call.respond(HttpStatusCode.OK)
    }

    post("/movies/{id}/unlike") {
        val principal = call.authenticateJWT(validateToken) ?: return@post
        val movieId = call.parameters["id"]?.toLongOrNull()
        if (movieId == null) {
            call.respond(HttpStatusCode.BadRequest, "Invalid movie ID")
            return@post
        }
        unlikeMovie(principal.claims.userId, movieId)
        call.respond(HttpStatusCode.OK)
    }

    get("/movies/{id}/reviews") {
        val movieId = call.parameters["id"]?.toIntOrNull()
        if (movieId == null) {
            call.respond(HttpStatusCode.BadRequest, "Invalid movie ID")
            return@get
        }
        val page = call.parameters["page"]?.toIntOrNull() ?: 1
        val userId = call.tryAuthenticateJWT(validateToken)?.claims?.userId
        call.respond(getMovieReviews(page = page, userId = userId, movieId = movieId))
    }

    get("/movies/recent/{userId}") {
        val userId = call.parameters["userId"] ?: ""
        call.respond(getRecentMovies(userId))
    }

    get("/movies/watched/{userId}") {
        val userId = call.parameters["userId"] ?: ""
        val page = call.parameters["page"]?.toIntOrNull() ?: 1
        call.respond(getUserWatchedMovies(userId, page))
    }

    get("/movies/favorites/{userId}") {
        val userId = call.parameters["userId"] ?: ""
        val page = call.parameters["page"]?.toIntOrNull() ?: 1
        call.respond(getUserFavoriteMovies(userId, page))
    }

    get("/movies/watchlist/{userId}") {
        val userId = call.parameters["userId"] ?: ""
        val page = call.parameters["page"]?.toIntOrNull() ?: 1
        call.respond(getUserWatchList(userId, page))
    }

    post("/movies/lists") {
        val principal = call.authenticateJWT(validateToken) ?: return@post
        val request = call.receive<CreateListRequest>()
        if (request.name.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "List name must not be blank"))
            return@post
        }
        val movieList = createMovieList(principal.claims.userId, request.name, request.description, request.movieIds)
        call.respond(HttpStatusCode.Created, movieList)
    }

    get("/movies/lists") {
        val page = call.parameters["page"]?.toIntOrNull() ?: 1
        val userId = call.parameters["userId"]
        call.respond(getMovieLists(page, userId))
    }

    get("/movies/lists/user") {
        val page = call.parameters["page"]?.toIntOrNull() ?: 1
        call.respond(getUserMovieLists(page))
    }

    get("/movies/lists/featured") {
        val page = call.parameters["page"]?.toIntOrNull() ?: 1
        call.respond(getFeaturedLists(page))
    }

    get("/movies/lists/{id}") {
        val listId = call.parameters["id"]?.toIntOrNull()
        if (listId == null) {
            call.respond(HttpStatusCode.BadRequest, "Invalid list ID")
            return@get
        }
        val page = call.parameters["page"]?.toIntOrNull() ?: 1
        call.respond(getMovieListDetail(listId, page))
    }

    delete("/movies/lists/{id}") {
        val principal = call.authenticateJWT(validateToken) ?: return@delete
        val listId = call.parameters["id"]?.toIntOrNull()
        if (listId == null) {
            call.respond(HttpStatusCode.BadRequest, "Invalid list ID")
            return@delete
        }
        deleteMovieList(principal.claims.userId, listId)
        call.respond(HttpStatusCode.NoContent)
    }

    post("/movies/lists/{id}/movies") {
        val principal = call.authenticateJWT(validateToken) ?: return@post
        val listId = call.parameters["id"]?.toIntOrNull()
        if (listId == null) {
            call.respond(HttpStatusCode.BadRequest, "Invalid list ID")
            return@post
        }
        val request = call.receive<AddMovieRequest>()
        addMovieToList(principal.claims.userId, listId, request.movieId)
        call.respond(HttpStatusCode.Created)
    }

    delete("/movies/lists/{id}/movies/{movieId}") {
        val principal = call.authenticateJWT(validateToken) ?: return@delete
        val listId = call.parameters["id"]?.toIntOrNull()
        val movieId = call.parameters["movieId"]?.toLongOrNull()
        if (listId == null || movieId == null) {
            call.respond(HttpStatusCode.BadRequest, "Invalid list or movie ID")
            return@delete
        }
        removeMovieFromList(principal.claims.userId, listId, movieId)
        call.respond(HttpStatusCode.NoContent)
    }

    post("/movies/{id}/status") {
        val principal = call.authenticateJWT(validateToken) ?: return@post
        val movieId = call.parameters["id"]?.toLongOrNull()
        if (movieId == null) {
            call.respond(HttpStatusCode.BadRequest, "Invalid movie ID")
            return@post
        }
        val request = call.receive<SetStatusRequest>()
        val validStatuses = setOf("watched", "want_to_watch", "dropped")
        if (request.status !in validStatuses) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Status must be one of: $validStatuses"))
            return@post
        }
        setMovieStatus(principal.claims.userId, movieId, request.status)
        call.respond(HttpStatusCode.OK)
    }

    post("/movies/{id}/rate") {
        val principal = call.authenticateJWT(validateToken) ?: return@post
        val movieId = call.parameters["id"]?.toLongOrNull()
        if (movieId == null) {
            call.respond(HttpStatusCode.BadRequest, "Invalid movie ID")
            return@post
        }
        val request = call.receive<RateMovieRequest>()
        if (request.rating < 0.5f || request.rating > 10.0f) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Rating must be between 0.5 and 10.0"))
            return@post
        }
        rateMovie(principal.claims.userId, movieId, request.rating)
        call.respond(HttpStatusCode.OK)
    }
}
