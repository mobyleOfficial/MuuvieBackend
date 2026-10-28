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
import org.mobyle.domain.usecase.follow.FollowAccount
import org.mobyle.domain.usecase.follow.GetFollowList
import org.mobyle.domain.usecase.follow.GetFollowStatus
import org.mobyle.domain.usecase.follow.UnfollowAccount
import org.mobyle.domain.usecase.social.SearchUsers
import org.mobyle.domain.usecase.users.ProfileAccess

/**
 * Follows (F6.3). Follow and unfollow are idempotent and answer the [FollowStatus], lists use
 * the Page envelope with the viewer's relation to each user, and `{userId}` also takes `me`
 * or an @handle.
 */
fun Route.getSocialRouting() {
    val validateToken by injection<ValidateToken>()
    val followAccount by injection<FollowAccount>()
    val unfollowAccount by injection<UnfollowAccount>()
    val getFollowStatus by injection<GetFollowStatus>()
    val getFollowList by injection<GetFollowList>()
    val searchUsers by injection<SearchUsers>()
    val profiles by injection<ProfileAccess>()

    // POST /users/{userId}/follow — follow a user
    post("/users/{userId}/follow") {
        val principal = call.authenticateJWT(validateToken) ?: return@post
        val targetId = profiles.resolveUserId(call.parameters["userId"]!!)
        call.respond(followAccount(principal.claims.userId, targetId))
    }

    // DELETE /users/{userId}/follow — unfollow a user
    delete("/users/{userId}/follow") {
        val principal = call.authenticateJWT(validateToken) ?: return@delete
        val targetId = profiles.resolveUserId(call.parameters["userId"]!!)
        call.respond(unfollowAccount(principal.claims.userId, targetId))
    }

    // GET /users/{userId}/followers — list followers of a user
    get("/users/{userId}/followers") {
        val principal = call.authenticateJWT(validateToken) ?: return@get
        val userId = call.parameters["userId"]
        if (userId.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_request", "User ID is required"))
            return@get
        }
        val pageRequest = call.pageRequest() ?: return@get
        val viewerId = principal.claims.userId
        val ownerId = if (userId == "me") viewerId else profiles.resolveUserId(userId)
        call.respond(HttpStatusCode.OK, getFollowList.followers(viewerId, ownerId, pageRequest))
    }

    // GET /users/{userId}/following — list who a user follows
    get("/users/{userId}/following") {
        val principal = call.authenticateJWT(validateToken) ?: return@get
        val userId = call.parameters["userId"]
        if (userId.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_request", "User ID is required"))
            return@get
        }
        val pageRequest = call.pageRequest() ?: return@get
        val viewerId = principal.claims.userId
        val ownerId = profiles.resolveUserId(userId)
        call.respond(HttpStatusCode.OK, getFollowList.following(viewerId, ownerId, pageRequest))
    }

    // GET /users/me/following — the current user's following list
    get("/users/me/following") {
        val principal = call.authenticateJWT(validateToken) ?: return@get
        val pageRequest = call.pageRequest() ?: return@get
        val viewerId = principal.claims.userId
        call.respond(HttpStatusCode.OK, getFollowList.following(viewerId, viewerId, pageRequest))
    }

    // "Seguidores recentes" (F6.3): my followers, newest follow first.
    get("/users/me/followers/recent") {
        val principal = call.authenticateJWT(validateToken) ?: return@get
        val pageRequest = call.pageRequest() ?: return@get
        val viewerId = principal.claims.userId
        call.respond(getFollowList.followers(viewerId, viewerId, pageRequest))
    }

    get("/users/{userId}/follow-status") {
        val principal = call.authenticateJWT(validateToken) ?: return@get
        val targetId = profiles.resolveUserId(call.parameters["userId"]!!)
        call.respond(getFollowStatus(principal.claims.userId, targetId))
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
