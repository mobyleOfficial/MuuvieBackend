package org.mobyle.routing

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import org.mobyle.di.injection
import org.mobyle.domain.usecase.movies.*

fun Route.getMoviesRouting() {
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

    get("/movies") {
        val id = call.parameters["id"]?.toLongOrNull()
        val tmdbId = call.parameters["tmdbId"]?.toIntOrNull()
        val filmowId = call.parameters["filmowId"]

        val detail = lookupMovieDetail(id, tmdbId, filmowId)
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
        val detail = lookupMovieDetail(null, tmdbId, null)
        if (detail == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Movie not found"))
        } else {
            call.respond(detail)
        }
    }

    get("/movies/{id}/reviews") {
        val movieId = call.parameters["id"]?.toIntOrNull()
        if (movieId == null) {
            call.respond(HttpStatusCode.BadRequest, "Invalid movie ID")
            return@get
        }
        val page = call.parameters["page"]?.toIntOrNull() ?: 1
        call.respond(getMovieReviews(page = page, movieId = movieId))
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
}
