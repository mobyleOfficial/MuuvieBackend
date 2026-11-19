package org.mobyle.routing

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import org.mobyle.data.remote.auth.authenticateJWT
import org.mobyle.di.injection
import org.mobyle.domain.usecase.auth.ValidateToken
import org.mobyle.domain.usecase.moderation.BlockUser
import org.mobyle.domain.usecase.moderation.GetBlockedUsers
import org.mobyle.domain.usecase.moderation.ReportContent
import org.mobyle.domain.usecase.moderation.UnblockUser
import org.mobyle.domain.usecase.users.DeleteAccount
import org.mobyle.domain.usecase.users.ProfileAccess

@Serializable
data class ReportRequest(
    val targetType: String,
    val targetId: String,
    val reason: String,
    val details: String? = null
)

@Serializable
data class DeleteAccountRequest(
    // A fresh Sign in with Apple authorization code, for accounts that sign in with Apple.
    val appleAuthorizationCode: String? = null,
    // The device cannot sign in with Apple (Android): delete without revoking the Apple token.
    val skipAppleRevocation: Boolean = false
)

/**
 * Account deletion, blocks and reports: what the stores require of an app with accounts and
 * user-generated content. `{userId}` takes an id or an @handle.
 */
fun Route.getModerationRouting() {
    val validateToken by injection<ValidateToken>()
    val profiles by injection<ProfileAccess>()
    val blockUser by injection<BlockUser>()
    val unblockUser by injection<UnblockUser>()
    val getBlockedUsers by injection<GetBlockedUsers>()
    val reportContent by injection<ReportContent>()
    val deleteAccount by injection<DeleteAccount>()

    delete("/users/me") {
        val principal = call.authenticateJWT(validateToken) ?: return@delete
        val request = runCatching { call.receive<DeleteAccountRequest>() }.getOrNull() ?: DeleteAccountRequest()
        val token = call.request.headers["Authorization"]!!.removePrefix("Bearer ")
        deleteAccount(principal.claims.userId, token, request.appleAuthorizationCode, request.skipAppleRevocation)
        call.respond(HttpStatusCode.NoContent)
    }

    get("/users/me/blocks") {
        val principal = call.authenticateJWT(validateToken) ?: return@get
        val page = call.pageRequest() ?: return@get
        call.respond(getBlockedUsers(principal.claims.userId, page))
    }

    post("/users/{userId}/block") {
        val principal = call.authenticateJWT(validateToken) ?: return@post
        val targetId = profiles.resolveUserId(call.parameters["userId"]!!)
        call.respond(blockUser(principal.claims.userId, targetId))
    }

    delete("/users/{userId}/block") {
        val principal = call.authenticateJWT(validateToken) ?: return@delete
        val targetId = profiles.resolveUserId(call.parameters["userId"]!!)
        call.respond(unblockUser(principal.claims.userId, targetId))
    }

    post("/reports") {
        val principal = call.authenticateJWT(validateToken) ?: return@post
        val request = call.receive<ReportRequest>()
        val receipt = reportContent(principal.claims.userId, request.targetType, request.targetId, request.reason, request.details)
        call.respond(HttpStatusCode.Created, receipt)
    }
}
