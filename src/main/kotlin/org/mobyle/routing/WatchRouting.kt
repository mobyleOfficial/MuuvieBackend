package org.mobyle.routing

import io.ktor.http.HttpStatusCode
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
import org.mobyle.domain.model.ValidationException
import org.mobyle.domain.usecase.auth.ValidateToken
import org.mobyle.domain.usecase.movies.GetMovieCineClubes
import org.mobyle.domain.usecase.movies.GetMovieEvents
import org.mobyle.domain.usecase.movies.GetMovieMaratonas
import org.mobyle.domain.usecase.movies.GetMovieTimeline
import org.mobyle.domain.usecase.watch.DeleteWatchLog
import org.mobyle.domain.usecase.watch.GetMovieCommunity
import org.mobyle.domain.usecase.watch.GetMovieLogs
import org.mobyle.domain.usecase.watch.GetMovieUserState
import org.mobyle.domain.usecase.watch.GetRecentEmotions
import org.mobyle.domain.usecase.watch.GetRecentGifs
import org.mobyle.domain.usecase.watch.GetWatchingNow
import org.mobyle.domain.usecase.watch.LogWatchSession
import org.mobyle.domain.usecase.watch.RatingInput
import org.mobyle.domain.usecase.watch.RemoveMovieRating
import org.mobyle.domain.usecase.watch.SearchGifs
import org.mobyle.domain.usecase.watch.SetMovieRating
import org.mobyle.domain.usecase.watch.SetWatchingNow
import org.mobyle.domain.usecase.watch.StartRewatch
import org.mobyle.domain.usecase.watch.UpdateWatchLog
import org.mobyle.domain.usecase.watch.WatchLogInput

/** POST /movies/tmdb/{tmdbId}/logs: the log fields plus the title, for movies not saved yet. */
@Serializable
data class TmdbWatchLogRequest(val title: String? = null, val log: WatchLogInput = WatchLogInput())

@Serializable
data class WatchingNowRequest(val movieId: Long? = null)

/** Watch sessions, the rating label (B13.1) and "assistindo agora" (phase 2, B2). Only mounted under /api/v1. */
fun Route.getWatchRouting() {
    val validateToken by injection<ValidateToken>()
    val logWatchSession by injection<LogWatchSession>()
    val getMovieLogs by injection<GetMovieLogs>()
    val updateWatchLog by injection<UpdateWatchLog>()
    val deleteWatchLog by injection<DeleteWatchLog>()
    val startRewatch by injection<StartRewatch>()
    val setMovieRating by injection<SetMovieRating>()
    val removeMovieRating by injection<RemoveMovieRating>()
    val getMovieUserState by injection<GetMovieUserState>()
    val setWatchingNow by injection<SetWatchingNow>()
    val getWatchingNow by injection<GetWatchingNow>()
    val getRecentEmotions by injection<GetRecentEmotions>()
    val getRecentGifs by injection<GetRecentGifs>()
    val searchGifs by injection<SearchGifs>()
    val getMovieCommunity by injection<GetMovieCommunity>()
    val getMovieEvents by injection<GetMovieEvents>()
    val getMovieCineClubes by injection<GetMovieCineClubes>()
    val getMovieMaratonas by injection<GetMovieMaratonas>()
    val getMovieTimeline by injection<GetMovieTimeline>()

    post("/movies/{id}/logs") {
        val principal = call.authenticateJWT(validateToken) ?: return@post
        val movieId = call.longParameter("id") ?: return@post
        val input = call.receive<WatchLogInput>()
        call.respond(HttpStatusCode.Created, logWatchSession(principal.claims.userId, movieId, null, null, input))
    }

    post("/movies/tmdb/{tmdbId}/logs") {
        val principal = call.authenticateJWT(validateToken) ?: return@post
        val tmdbId = call.longParameter("tmdbId")?.toInt() ?: return@post
        val request = call.receive<TmdbWatchLogRequest>()
        call.respond(
            HttpStatusCode.Created,
            logWatchSession(principal.claims.userId, null, tmdbId, request.title, request.log)
        )
    }

    get("/movies/{id}/logs") {
        val movieId = call.longParameter("id") ?: return@get
        val userId = call.parameters["userId"] ?: "me"
        val viewerId = if (userId == "me") {
            call.authenticateJWT(validateToken)?.claims?.userId ?: return@get
        } else {
            call.tryAuthenticateJWT(validateToken)?.claims?.userId
        }
        val page = call.pageRequest() ?: return@get
        call.respond(getMovieLogs(viewerId, if (userId == "me") viewerId!! else userId, movieId, page))
    }

    put("/logs/{id}") {
        val principal = call.authenticateJWT(validateToken) ?: return@put
        val logId = call.longParameter("id") ?: return@put
        val input = call.receive<WatchLogInput>()
        call.respond(updateWatchLog(principal.claims.userId, logId, input))
    }

    delete("/logs/{id}") {
        val principal = call.authenticateJWT(validateToken) ?: return@delete
        val logId = call.longParameter("id") ?: return@delete
        deleteWatchLog(principal.claims.userId, logId)
        call.respond(HttpStatusCode.NoContent)
    }

    post("/movies/{id}/rewatch") {
        val principal = call.authenticateJWT(validateToken) ?: return@post
        val movieId = call.longParameter("id") ?: return@post
        call.respond(startRewatch(principal.claims.userId, movieId))
    }

    put("/movies/{id}/rating") {
        val principal = call.authenticateJWT(validateToken) ?: return@put
        val movieId = call.longParameter("id") ?: return@put
        val input = call.receive<RatingInput>()
        call.respond(setMovieRating(principal.claims.userId, movieId, input))
    }

    delete("/movies/{id}/rating") {
        val principal = call.authenticateJWT(validateToken) ?: return@delete
        val movieId = call.longParameter("id") ?: return@delete
        removeMovieRating(principal.claims.userId, movieId)
        call.respond(HttpStatusCode.NoContent)
    }

    // Public: what the community did with the movie (F12.1).
    get("/movies/{id}/community") {
        val movieId = call.longParameter("id") ?: return@get
        call.respond(getMovieCommunity(movieId))
    }

    // Public: open maratonas the movie counts for and CineClubes that screen it (F12.1).
    get("/movies/{id}/events") {
        val viewerId = call.tryAuthenticateJWT(validateToken)?.claims?.userId
        val movieId = call.longParameter("id") ?: return@get
        call.respond(getMovieEvents(viewerId, movieId))
    }

    // Public: every CineClube with a session of the movie, finished included (B13.5).
    get("/movies/{id}/cineclubes") {
        val viewerId = call.tryAuthenticateJWT(validateToken)?.claims?.userId
        val movieId = call.longParameter("id") ?: return@get
        val page = call.pageRequest() ?: return@get
        call.respond(getMovieCineClubes(viewerId, movieId, page))
    }

    // Public: maratonas where the movie completed a challenge for someone (B13.5).
    get("/movies/{id}/maratonas") {
        val viewerId = call.tryAuthenticateJWT(validateToken)?.claims?.userId
        val movieId = call.longParameter("id") ?: return@get
        val page = call.pageRequest() ?: return@get
        call.respond(getMovieMaratonas(viewerId, movieId, page))
    }

    // Public: public sessions and ratings of the movie per month (B13.5).
    get("/movies/{id}/timeline") {
        val movieId = call.longParameter("id") ?: return@get
        val months = call.parameters["months"]?.let {
            it.toIntOrNull() ?: throw ValidationException("months", "months must be a number")
        } ?: GetMovieTimeline.DEFAULT_MONTHS
        call.respond(getMovieTimeline(movieId, months))
    }

    get("/movies/{id}/me") {
        val principal = call.authenticateJWT(validateToken) ?: return@get
        val movieId = call.longParameter("id") ?: return@get
        call.respond(getMovieUserState(principal.claims.userId, movieId))
    }

    put("/users/me/watching-now") {
        val principal = call.authenticateJWT(validateToken) ?: return@put
        val request = call.receive<WatchingNowRequest>()
        setWatchingNow(principal.claims.userId, request.movieId)
        call.respond(HttpStatusCode.NoContent)
    }

    get("/users/{userId}/watching-now") {
        val userId = call.parameters["userId"]!!
        val ownerId = if (userId == "me") {
            call.authenticateJWT(validateToken)?.claims?.userId ?: return@get
        } else {
            userId
        }
        val watchingNow = getWatchingNow(ownerId)
        if (watchingNow == null) call.respond(HttpStatusCode.NoContent) else call.respond(watchingNow)
    }

    get("/users/me/emotions/recent") {
        val principal = call.authenticateJWT(validateToken) ?: return@get
        call.respond(getRecentEmotions(principal.claims.userId))
    }

    get("/users/me/gifs/recent") {
        val principal = call.authenticateJWT(validateToken) ?: return@get
        call.respond(getRecentGifs(principal.claims.userId))
    }

    get("/gifs/search") {
        call.authenticateJWT(validateToken) ?: return@get
        call.respond(searchGifs(call.parameters["q"].orEmpty(), call.parameters["cursor"]))
    }
}
