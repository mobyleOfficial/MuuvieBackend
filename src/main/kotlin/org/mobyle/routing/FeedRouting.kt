package org.mobyle.routing

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import kotlinx.serialization.Serializable
import org.mobyle.data.remote.auth.authenticateJWT
import org.mobyle.data.remote.auth.tryAuthenticateJWT
import org.mobyle.di.injection
import org.mobyle.domain.usecase.auth.ValidateToken
import org.mobyle.domain.usecase.feed.GetFeed
import org.mobyle.domain.usecase.feed.GetMovieActivities
import org.mobyle.domain.usecase.feed.GetProfileActivities
import org.mobyle.domain.usecase.feed.HideActivity
import org.mobyle.domain.usecase.feed.LoveActivity
import org.mobyle.domain.usecase.feed.UnloveActivity
import org.mobyle.domain.usecase.users.ProfileAccess

@Serializable
data class HideActivityRequest(val hidden: Boolean = true)

/**
 * The feed of the v1 API (B6): the Atts tab, a profile's activities, the activities of
 * people I follow about a movie, "amei" and hiding my own. Lists use the cursor envelope.
 */
fun Route.getFeedRouting() {
    val validateToken by injection<ValidateToken>()
    val getFeed by injection<GetFeed>()
    val getProfileActivities by injection<GetProfileActivities>()
    val getMovieActivities by injection<GetMovieActivities>()
    val loveActivity by injection<LoveActivity>()
    val unloveActivity by injection<UnloveActivity>()
    val hideActivity by injection<HideActivity>()
    val profiles by injection<ProfileAccess>()

    get("/feed") {
        val principal = call.authenticateJWT(validateToken) ?: return@get
        call.respond(
            getFeed(
                principal.claims.userId,
                call.parameters["scope"],
                call.parameters["cursor"],
                call.parameters["limit"]?.let { it.toIntOrNull() ?: 0 }
            )
        )
    }

    get("/users/{userId}/activities") {
        val userId = call.parameters["userId"]!!
        val viewerId = if (userId == "me") {
            call.authenticateJWT(validateToken)?.claims?.userId ?: return@get
        } else {
            call.tryAuthenticateJWT(validateToken)?.claims?.userId
        }
        val ownerId = if (userId == "me") viewerId!! else profiles.resolveUserId(userId)
        call.respond(
            getProfileActivities(
                viewerId, ownerId, call.parameters["cursor"], call.parameters["limit"]?.let { it.toIntOrNull() ?: 0 }
            )
        )
    }

    // scope=following (default) or me need login; scope=all is everything I may see, also for
    // guests. type=ratings|updates narrows it (B13.3).
    get("/movies/{movieId}/activities") {
        val scope = call.parameters["scope"]
        val viewerId = if (scope == "all") {
            call.tryAuthenticateJWT(validateToken)?.claims?.userId
        } else {
            call.authenticateJWT(validateToken)?.claims?.userId ?: return@get
        }
        val movieId = call.longParameter("movieId") ?: return@get
        call.respond(
            getMovieActivities(
                viewerId, movieId, scope, call.parameters["type"], call.parameters["cursor"],
                call.parameters["limit"]?.let { it.toIntOrNull() ?: 0 }
            )
        )
    }

    put("/activities/{activityId}/love") {
        val principal = call.authenticateJWT(validateToken) ?: return@put
        val activityId = call.longParameter("activityId") ?: return@put
        call.respond(loveActivity(principal.claims.userId, activityId))
    }

    delete("/activities/{activityId}/love") {
        val principal = call.authenticateJWT(validateToken) ?: return@delete
        val activityId = call.longParameter("activityId") ?: return@delete
        call.respond(unloveActivity(principal.claims.userId, activityId))
    }

    put("/activities/{activityId}/hidden") {
        val principal = call.authenticateJWT(validateToken) ?: return@put
        val activityId = call.longParameter("activityId") ?: return@put
        val request = call.receive<HideActivityRequest>()
        hideActivity(principal.claims.userId, activityId, request.hidden)
        call.respond(HttpStatusCode.NoContent)
    }
}
