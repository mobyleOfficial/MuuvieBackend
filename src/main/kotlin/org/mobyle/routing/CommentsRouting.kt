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
import org.mobyle.data.remote.auth.tryAuthenticateJWT
import org.mobyle.di.injection
import org.mobyle.domain.model.ValidationException
import org.mobyle.domain.usecase.auth.ValidateToken
import org.mobyle.domain.usecase.comments.DeleteComment
import org.mobyle.domain.usecase.comments.GetThreadComments
import org.mobyle.domain.usecase.comments.PostComment

@Serializable
data class PostCommentRequest(
    val targetType: String,
    val targetId: Long,
    val parentId: Long? = null,
    val body: String,
    val hasSpoiler: Boolean = false
)

/** Comments on watch logs and ratings (F2.8, B2). */
fun Route.getCommentsRouting() {
    val validateToken by injection<ValidateToken>()
    val getThreadComments by injection<GetThreadComments>()
    val postComment by injection<PostComment>()
    val deleteComment by injection<DeleteComment>()

    get("/comments") {
        val viewerId = call.tryAuthenticateJWT(validateToken)?.claims?.userId
        val targetType = call.parameters["targetType"] ?: throw ValidationException("targetType", "targetType is required")
        val targetId = call.parameters["targetId"]?.toLongOrNull()?.takeIf { it > 0 }
            ?: throw ValidationException("targetId", "Invalid targetId")
        val page = call.pageRequest() ?: return@get
        call.respond(getThreadComments(viewerId, targetType, targetId, page))
    }

    post("/comments") {
        val principal = call.authenticateJWT(validateToken) ?: return@post
        val request = call.receive<PostCommentRequest>()
        val comment = postComment(
            principal.claims.userId, request.targetType, request.targetId, request.parentId, request.body, request.hasSpoiler
        )
        call.respond(HttpStatusCode.Created, comment)
    }

    delete("/comments/{id}") {
        val principal = call.authenticateJWT(validateToken) ?: return@delete
        val commentId = call.longParameter("id") ?: return@delete
        deleteComment(principal.claims.userId, commentId)
        call.respond(HttpStatusCode.NoContent)
    }
}
