package org.mobyle.routing

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import org.mobyle.data.local.user.UserDatabaseDataSource
import org.mobyle.data.remote.auth.authenticateJWT
import org.mobyle.di.injection
import org.mobyle.domain.model.UserProfile
import org.mobyle.domain.repository.ImportRepository
import org.mobyle.domain.usecase.auth.ValidateToken
import org.mobyle.domain.usecase.profile.UpdateProfileResult
import org.mobyle.domain.usecase.profile.UpdateUserProfile

/** The signed-in user's account: GET and PUT /profile. */
fun Route.getProfileRouting() {
    val updateUserProfile by injection<UpdateUserProfile>()
    val validateToken by injection<ValidateToken>()
    val userDatabaseDataSource by injection<UserDatabaseDataSource>()
    val importRepository by injection<ImportRepository>()

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
            isScraping = importRepository.hasRunningJob(user.id)
        )
        call.respond(HttpStatusCode.OK, profile)
    }

    put("/profile") {
        val principal = call.authenticateJWT(validateToken) ?: return@put
        val profile = call.receive<UserProfile>()
        when (val result = updateUserProfile(profile.copy(id = principal.claims.userId))) {
            UpdateProfileResult.Updated -> Unit
            UpdateProfileResult.UsernameTaken -> {
                call.respond(
                    HttpStatusCode.Conflict,
                    org.mobyle.data.remote.auth.ErrorResponse("username_taken", "Username is already in use")
                )
                return@put
            }
            is UpdateProfileResult.Invalid -> {
                call.respond(
                    HttpStatusCode.BadRequest,
                    org.mobyle.data.remote.auth.ErrorResponse("validation_failed", "Invalid ${result.field}")
                )
                return@put
            }
        }

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
            isScraping = importRepository.hasRunningJob(user.id)
        )
        call.respond(HttpStatusCode.OK, updatedProfile)
    }
}
