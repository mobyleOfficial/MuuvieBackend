package org.mobyle.routing

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.request.receiveNullable
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.serialization.Serializable
import org.mobyle.data.remote.auth.authenticateJWT
import org.mobyle.di.injection
import org.mobyle.domain.model.NotificationPrefs
import org.mobyle.domain.usecase.auth.ValidateToken
import org.mobyle.domain.usecase.messages.GetConversation
import org.mobyle.domain.usecase.messages.GetConversationWith
import org.mobyle.domain.usecase.messages.GetConversations
import org.mobyle.domain.usecase.messages.GetMessages
import org.mobyle.domain.usecase.messages.GetUnreadCount
import org.mobyle.domain.usecase.messages.MarkConversationRead
import org.mobyle.domain.usecase.messages.SendMessage
import org.mobyle.domain.usecase.notifications.GetNotificationPrefs
import org.mobyle.domain.usecase.notifications.RegisterDevice
import org.mobyle.domain.usecase.notifications.RemoveDevice
import org.mobyle.domain.usecase.notifications.SaveNotificationPrefs
import org.mobyle.domain.usecase.users.ProfileAccess

@Serializable
data class SendMessageRequest(val body: String = "")

@Serializable
data class DeviceRequest(val platform: String = "", val token: String = "", val locale: String? = null)

@Serializable
data class RemoveDeviceRequest(val token: String = "")

/**
 * Sussurros (F6.5, F6.6) and push devices (F6.7) of the v1 API. New messages and the unread
 * badge also arrive on the WebSocket as `message` and `unread`.
 */
fun Route.getMessagesRouting() {
    val validateToken by injection<ValidateToken>()
    val getConversations by injection<GetConversations>()
    val getConversation by injection<GetConversation>()
    val getConversationWith by injection<GetConversationWith>()
    val getMessages by injection<GetMessages>()
    val sendMessage by injection<SendMessage>()
    val markConversationRead by injection<MarkConversationRead>()
    val getUnreadCount by injection<GetUnreadCount>()
    val registerDevice by injection<RegisterDevice>()
    val removeDevice by injection<RemoveDevice>()
    val getNotificationPrefs by injection<GetNotificationPrefs>()
    val saveNotificationPrefs by injection<SaveNotificationPrefs>()
    val profiles by injection<ProfileAccess>()

    get("/conversations") {
        val principal = call.authenticateJWT(validateToken) ?: return@get
        call.respond(
            getConversations(principal.claims.userId, call.parameters["cursor"], call.parameters["limit"]?.let { it.toIntOrNull() ?: 0 })
        )
    }

    get("/conversations/{conversationId}") {
        val principal = call.authenticateJWT(validateToken) ?: return@get
        val conversationId = call.longParameter("conversationId") ?: return@get
        call.respond(getConversation(principal.claims.userId, conversationId))
    }

    get("/conversations/{conversationId}/messages") {
        val principal = call.authenticateJWT(validateToken) ?: return@get
        val conversationId = call.longParameter("conversationId") ?: return@get
        call.respond(
            getMessages(
                principal.claims.userId,
                conversationId,
                call.parameters["cursor"],
                call.parameters["limit"]?.let { it.toIntOrNull() ?: 0 }
            )
        )
    }

    put("/conversations/{conversationId}/read") {
        val principal = call.authenticateJWT(validateToken) ?: return@put
        val conversationId = call.longParameter("conversationId") ?: return@put
        call.respond(markConversationRead(principal.claims.userId, conversationId))
    }

    post("/users/{userId}/messages") {
        val principal = call.authenticateJWT(validateToken) ?: return@post
        val recipientId = profiles.resolveUserId(call.parameters["userId"]!!)
        val request = call.receive<SendMessageRequest>()
        call.respond(HttpStatusCode.Created, sendMessage(principal.claims.userId, recipientId, request.body))
    }

    get("/users/{userId}/conversation") {
        val principal = call.authenticateJWT(validateToken) ?: return@get
        val otherId = profiles.resolveUserId(call.parameters["userId"]!!)
        call.respond(getConversationWith(principal.claims.userId, otherId))
    }

    get("/users/me/unread-count") {
        val principal = call.authenticateJWT(validateToken) ?: return@get
        call.respond(getUnreadCount(principal.claims.userId))
    }

    put("/users/me/devices") {
        val principal = call.authenticateJWT(validateToken) ?: return@put
        val request = call.receive<DeviceRequest>()
        registerDevice(principal.claims.userId, request.platform, request.token, request.locale)
        call.respond(HttpStatusCode.NoContent)
    }

    // The token goes in the body or, for clients that cannot send a body on DELETE, in ?token=.
    delete("/users/me/devices") {
        val principal = call.authenticateJWT(validateToken) ?: return@delete
        val token = call.parameters["token"] ?: call.receiveNullable<RemoveDeviceRequest>()?.token.orEmpty()
        removeDevice(principal.claims.userId, token)
        call.respond(HttpStatusCode.NoContent)
    }

    get("/users/me/notification-prefs") {
        val principal = call.authenticateJWT(validateToken) ?: return@get
        call.respond(getNotificationPrefs(principal.claims.userId))
    }

    put("/users/me/notification-prefs") {
        val principal = call.authenticateJWT(validateToken) ?: return@put
        val prefs = call.receive<NotificationPrefs>()
        call.respond(saveNotificationPrefs(principal.claims.userId, prefs))
    }
}
