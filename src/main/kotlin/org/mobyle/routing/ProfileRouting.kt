package org.mobyle.routing

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import org.mobyle.data.remote.auth.authenticateJWT
import org.mobyle.di.injection
import org.mobyle.domain.model.UserProfile
import org.mobyle.data.local.user.UserDatabaseDataSource
import org.mobyle.domain.usecase.profile.GetPublicProfile
import org.mobyle.domain.usecase.profile.GetUserProfile
import org.mobyle.domain.usecase.profile.UpdateUserProfile
import org.mobyle.domain.usecase.auth.ValidateToken
import org.mobyle.data.service.ScrapeStatusManager

fun Route.getProfileRouting() {
    val getUserProfile by injection<GetUserProfile>()
    val updateUserProfile by injection<UpdateUserProfile>()
    val getPublicProfile by injection<GetPublicProfile>()
    val validateToken by injection<ValidateToken>()
    val userDatabaseDataSource by injection<UserDatabaseDataSource>()
    val scrapeStatusManager by injection<ScrapeStatusManager>()

    get("/profile") {
        val principal = call.authenticateJWT(validateToken) ?: return@get
        val email = principal.claims.email

        val user = userDatabaseDataSource.findByEmail(email)
        if (user == null) {
            call.respond(
                HttpStatusCode.NotFound,
                org.mobyle.data.remote.auth.ErrorResponse("user_not_found", "User not found")
            )
            return@get
        }

        val profile = UserProfile(
            id = user.id,
            photoUrl = user.avatar ?: "",
            username = user.username,
            bio = user.bio ?: "",
            moviesWatchedCount = userDatabaseDataSource.countWatchedMovies(user.id),
            followingCount = userDatabaseDataSource.countFollowing(user.id),
            followersCount = userDatabaseDataSource.countFollowers(user.id),
            recentMovies = userDatabaseDataSource.getRecentWatchedMovies(user.id, limit = 10),
            isScraping = scrapeStatusManager.isScraping(user.id)
        )
        call.respond(HttpStatusCode.OK, profile)
    }

    put("/profile") {
        val principal = call.authenticateJWT(validateToken) ?: return@put
        val profile = call.receive<UserProfile>()
        updateUserProfile(profile.copy(id = principal.claims.userId))

        val user = userDatabaseDataSource.findByEmail(principal.claims.email)
        if (user == null) {
            call.respond(
                HttpStatusCode.NotFound,
                org.mobyle.data.remote.auth.ErrorResponse("user_not_found", "User not found")
            )
            return@put
        }
        val updatedProfile = UserProfile(
            id = user.id,
            photoUrl = user.avatar ?: "",
            username = user.username,
            bio = user.bio ?: "",
            moviesWatchedCount = userDatabaseDataSource.countWatchedMovies(user.id),
            followingCount = userDatabaseDataSource.countFollowing(user.id),
            followersCount = userDatabaseDataSource.countFollowers(user.id),
            recentMovies = userDatabaseDataSource.getRecentWatchedMovies(user.id, limit = 10),
            isScraping = scrapeStatusManager.isScraping(user.id)
        )
        call.respond(HttpStatusCode.OK, updatedProfile)
    }

    get("/profile/{userId}") {
        val userId = call.parameters["userId"]
        if (userId.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, "User ID is required")
            return@get
        }
        call.respond(getPublicProfile(userId))
    }
}
