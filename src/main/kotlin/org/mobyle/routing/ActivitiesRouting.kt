package org.mobyle.routing

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import org.mobyle.data.remote.auth.authenticateJWT
import org.mobyle.di.injection
import org.mobyle.domain.model.MovieReviewDraft
import org.mobyle.domain.usecase.activities.GetFriendsActivities
import org.mobyle.domain.usecase.activities.GetUserActivities
import org.mobyle.domain.usecase.activities.SubmitReview
import org.mobyle.domain.usecase.auth.ValidateToken
import org.mobyle.domain.usecase.movies.LikeReview
import org.mobyle.domain.usecase.movies.UnlikeReview

fun Route.getActivitiesRouting() {
    val getUserActivities by injection<GetUserActivities>()
    val getFriendsActivities by injection<GetFriendsActivities>()
    val submitReview by injection<SubmitReview>()
    val validateToken by injection<ValidateToken>()
    val likeReview by injection<LikeReview>()
    val unlikeReview by injection<UnlikeReview>()

    get("/activities/{userId}") {
        val userId = call.parameters["userId"]
        if (userId.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, "User ID is required")
            return@get
        }
        call.respond(getUserActivities(userId))
    }

    get("/activities/friends") {
        val principal = call.authenticateJWT(validateToken) ?: return@get
        val page = call.parameters["page"]?.toIntOrNull() ?: 1
        call.respond(getFriendsActivities(principal.claims.userId, page))
    }

    post("/reviews") {
        val principal = call.authenticateJWT(validateToken) ?: return@post
        val draft = call.receive<MovieReviewDraft>()
        submitReview(principal.claims.userId, draft)
        call.respond(HttpStatusCode.Created)
    }

    post("/reviews/{reviewId}/like") {
        val principal = call.authenticateJWT(validateToken) ?: return@post
        val reviewId = call.parameters["reviewId"]
        if (reviewId.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, "Review ID is required")
            return@post
        }
        likeReview(principal.claims.userId, reviewId)
        call.respond(HttpStatusCode.OK)
    }

    post("/reviews/{reviewId}/unlike") {
        val principal = call.authenticateJWT(validateToken) ?: return@post
        val reviewId = call.parameters["reviewId"]
        if (reviewId.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, "Review ID is required")
            return@post
        }
        unlikeReview(principal.claims.userId, reviewId)
        call.respond(HttpStatusCode.OK)
    }
}
