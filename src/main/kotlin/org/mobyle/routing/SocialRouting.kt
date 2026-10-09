package org.mobyle.routing

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import org.mobyle.data.remote.auth.ErrorResponse
import org.mobyle.data.remote.auth.authenticateJWT
import org.mobyle.di.injection
import org.mobyle.domain.usecase.auth.ValidateToken
import org.mobyle.domain.usecase.social.FollowUser
import org.mobyle.domain.usecase.social.GetFollowers
import org.mobyle.domain.usecase.social.GetFollowing
import org.mobyle.domain.usecase.social.GetMyFollowing
import org.mobyle.domain.usecase.social.SearchUsers
import org.mobyle.domain.usecase.social.UnfollowUser

fun Route.getSocialRouting() {
    val validateToken by injection<ValidateToken>()
    val followUser by injection<FollowUser>()
    val unfollowUser by injection<UnfollowUser>()
    val getFollowers by injection<GetFollowers>()
    val getFollowing by injection<GetFollowing>()
    val getMyFollowing by injection<GetMyFollowing>()
    val searchUsers by injection<SearchUsers>()

    // POST /users/{userId}/follow — follow a user
    post("/users/{userId}/follow") {
        val principal = call.authenticateJWT(validateToken) ?: return@post
        val targetId = call.parameters["userId"]
        if (targetId.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_request", "User ID is required"))
            return@post
        }
        if (targetId == principal.claims.userId) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_request", "Cannot follow yourself"))
            return@post
        }
        val followed = followUser(principal.claims.userId, targetId)
        if (followed) {
            call.respond(HttpStatusCode.OK, mapOf("status" to "following"))
        } else {
            call.respond(HttpStatusCode.Conflict, ErrorResponse("already_following", "Already following this user"))
        }
    }

    // DELETE /users/{userId}/follow — unfollow a user
    delete("/users/{userId}/follow") {
        val principal = call.authenticateJWT(validateToken) ?: return@delete
        val targetId = call.parameters["userId"]
        if (targetId.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_request", "User ID is required"))
            return@delete
        }
        val unfollowed = unfollowUser(principal.claims.userId, targetId)
        if (unfollowed) {
            call.respond(HttpStatusCode.OK, mapOf("status" to "unfollowed"))
        } else {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("not_following", "Not following this user"))
        }
    }

    // GET /users/{userId}/followers — list followers of a user
    get("/users/{userId}/followers") {
        val principal = call.authenticateJWT(validateToken) ?: return@get
        val userId = call.parameters["userId"]
        if (userId.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_request", "User ID is required"))
            return@get
        }
        val page = call.parameters["page"]?.toIntOrNull() ?: 1
        call.respond(HttpStatusCode.OK, getFollowers(userId, page))
    }

    // GET /users/{userId}/following — list who a user follows
    get("/users/{userId}/following") {
        val principal = call.authenticateJWT(validateToken) ?: return@get
        val userId = call.parameters["userId"]
        if (userId.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_request", "User ID is required"))
            return@get
        }
        val page = call.parameters["page"]?.toIntOrNull() ?: 1
        call.respond(HttpStatusCode.OK, getFollowing(userId, page))
    }

    // GET /users/me/following — current user's following list (for Friends tab)
    get("/users/me/following") {
        val principal = call.authenticateJWT(validateToken) ?: return@get
        call.respond(HttpStatusCode.OK, getMyFollowing(principal.claims.userId))
    }

    // GET /users/search?q= — search for users
    get("/users/search") {
        val principal = call.authenticateJWT(validateToken) ?: return@get
        val query = call.parameters["q"] ?: ""
        if (query.length < 2) {
            call.respond(HttpStatusCode.OK, emptyList<Any>())
            return@get
        }
        call.respond(HttpStatusCode.OK, searchUsers(query, principal.claims.userId))
    }
}
