package org.mobyle.routing

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import org.mobyle.data.remote.auth.tryAuthenticateJWT
import org.mobyle.di.injection
import org.mobyle.domain.usecase.auth.ValidateToken
import org.mobyle.domain.usecase.movies.*
import org.mobyle.domain.usecase.watch.GetMovieUserState

/** The movie catalog: detail, search, discover and TMDB vocabularies. */
fun Route.getMoviesRouting() {
    val validateToken by injection<ValidateToken>()
    val getTrendingMovies by injection<GetTrendingMovies>()
    val searchMovies by injection<SearchMovies>()
    val discoverMovies by injection<DiscoverMovies>()
    val getGenres by injection<GetGenres>()
    val getCountries by injection<GetCountries>()
    val getLanguages by injection<GetLanguages>()
    val lookupMovieDetail by injection<LookupMovieDetail>()
    val getMovieUserState by injection<GetMovieUserState>()
    val attachMovieStates by injection<AttachMovieStates>()

    get("/movies") {
        val id = call.parameters["id"]?.toLongOrNull()
        val tmdbId = call.parameters["tmdbId"]?.toIntOrNull()
        val filmowId = call.parameters["filmowId"]
        val userId = call.tryAuthenticateJWT(validateToken)?.claims?.userId

        val detail = lookupMovieDetail(id, tmdbId, filmowId)
        if (detail == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Movie not found"))
        } else {
            call.respond(if (userId != null && detail.id > 0) detail.copy(me = getMovieUserState(userId, detail.id)) else detail)
        }
    }

    get("/movies/trending") {
        val page = call.parameters["page"]?.toIntOrNull() ?: 1
        val viewerId = call.tryAuthenticateJWT(validateToken)?.claims?.userId
        call.respond(attachMovieStates(viewerId, getTrendingMovies(page)))
    }

    get("/movies/search") {
        val query = call.parameters["query"]?.trim()
        if (query.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, "Query parameter is required")
            return@get
        }
        val page = call.parameters["page"]?.toIntOrNull() ?: 1
        val viewerId = call.tryAuthenticateJWT(validateToken)?.claims?.userId
        call.respond(attachMovieStates(viewerId, searchMovies(query, page)))
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
        val viewerId = call.tryAuthenticateJWT(validateToken)?.claims?.userId

        call.respond(
            attachMovieStates(viewerId, discoverMovies(
                page = page,
                year = year,
                releaseDateGte = releaseDateGte,
                releaseDateLte = releaseDateLte,
                sortBy = sortBy,
                genres = genres,
                language = language,
                country = country,
                voteCountGte = voteCountGte
            ))
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
        val detail = lookupMovieDetail(null, tmdbId, null)
        if (detail == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Movie not found"))
        } else {
            call.respond(if (userId != null && detail.id > 0) detail.copy(me = getMovieUserState(userId, detail.id)) else detail)
        }
    }
}
