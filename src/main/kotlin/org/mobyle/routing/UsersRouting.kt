package org.mobyle.routing

import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import kotlinx.serialization.Serializable
import org.mobyle.data.remote.auth.authenticateJWT
import org.mobyle.data.remote.auth.tryAuthenticateJWT
import org.mobyle.di.injection
import org.mobyle.domain.usecase.auth.ValidateToken
import org.mobyle.domain.usecase.users.CheckHandleAvailability
import org.mobyle.domain.usecase.users.GetMyProfile
import org.mobyle.domain.usecase.users.GetProfile
import org.mobyle.domain.usecase.users.GetProfileHighlights
import org.mobyle.domain.usecase.users.GetProfileStats
import org.mobyle.domain.usecase.users.HighlightInput
import org.mobyle.domain.usecase.users.SaveProfileHighlights
import org.mobyle.domain.usecase.users.UpdateMyProfile
import org.mobyle.domain.usecase.watch.GetUserDiary

@Serializable
data class UpdateProfileRequest(
    val displayName: String? = null,
    // Null keeps the current handle.
    val handle: String? = null,
    val pronouns: String? = null,
    val bio: String? = null,
    val links: List<String> = emptyList(),
    val accentColor: String? = null,
    val avatarUrl: String? = null,
    val bannerUrl: String? = null
)

@Serializable
data class HighlightRequest(
    val type: String,
    val enabled: Boolean = true,
    val movieIds: List<Long> = emptyList(),
    val shelfId: Long? = null,
    val locadoraId: Long? = null,
    val soundtrackUrl: String? = null
)

@Serializable
data class HighlightsRequest(val highlights: List<HighlightRequest> = emptyList())

/**
 * Profiles of the v1 API (B5). `{userId}` takes a user id or a handle with the @
 * (`/users/@cinefila`), so a profile link works with either. Only mounted under /api/v1;
 * the legacy /profile routes stay until B11.
 */
fun Route.getUsersRouting() {
    val validateToken by injection<ValidateToken>()
    val getMyProfile by injection<GetMyProfile>()
    val getProfile by injection<GetProfile>()
    val getProfileStats by injection<GetProfileStats>()
    val updateMyProfile by injection<UpdateMyProfile>()
    val checkHandleAvailability by injection<CheckHandleAvailability>()
    val getProfileHighlights by injection<GetProfileHighlights>()
    val saveProfileHighlights by injection<SaveProfileHighlights>()
    val getUserDiary by injection<GetUserDiary>()

    get("/users/me") {
        val principal = call.authenticateJWT(validateToken) ?: return@get
        call.respond(getMyProfile(principal.claims.userId))
    }

    put("/users/me") {
        val principal = call.authenticateJWT(validateToken) ?: return@put
        val request = call.receive<UpdateProfileRequest>()
        call.respond(
            updateMyProfile(
                principal.claims.userId,
                request.displayName,
                request.handle,
                request.pronouns,
                request.bio,
                request.links,
                request.accentColor,
                request.avatarUrl,
                request.bannerUrl
            )
        )
    }

    get("/users/me/highlights") {
        val principal = call.authenticateJWT(validateToken) ?: return@get
        call.respond(getProfileHighlights(principal.claims.userId, principal.claims.userId))
    }

    put("/users/me/highlights") {
        val principal = call.authenticateJWT(validateToken) ?: return@put
        val request = call.receive<HighlightsRequest>()
        val inputs = request.highlights.map {
            HighlightInput(it.type, it.enabled, it.movieIds, it.shelfId, it.locadoraId, it.soundtrackUrl)
        }
        call.respond(saveProfileHighlights(principal.claims.userId, inputs))
    }

    get("/handles/{handle}/available") {
        val viewerId = call.tryAuthenticateJWT(validateToken)?.claims?.userId
        call.respond(checkHandleAvailability(call.parameters["handle"]!!, viewerId))
    }

    get("/users/{userId}") {
        val viewerId = call.tryAuthenticateJWT(validateToken)?.claims?.userId
        call.respond(getProfile(viewerId, call.parameters["userId"]!!))
    }

    get("/users/{userId}/stats") {
        val viewerId = call.tryAuthenticateJWT(validateToken)?.claims?.userId
        call.respond(getProfileStats(viewerId, call.parameters["userId"]!!))
    }

    get("/users/{userId}/highlights") {
        val viewerId = call.tryAuthenticateJWT(validateToken)?.claims?.userId
        call.respond(getProfileHighlights(viewerId, call.parameters["userId"]!!))
    }

    get("/users/{userId}/logs") {
        val userId = call.parameters["userId"]!!
        val viewerId = if (userId == "me") {
            call.authenticateJWT(validateToken)?.claims?.userId ?: return@get
        } else {
            call.tryAuthenticateJWT(validateToken)?.claims?.userId
        }
        val page = call.pageRequest() ?: return@get
        call.respond(getUserDiary(viewerId, if (userId == "me") viewerId!! else userId, page))
    }
}
