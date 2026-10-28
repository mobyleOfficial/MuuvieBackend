package org.mobyle.domain.model

import kotlinx.serialization.Serializable

/** Which push notifications a user wants (F6.7). Everything is on until turned off. */
@Serializable
data class NotificationPrefs(
    val messages: Boolean = true,
    val newFollowers: Boolean = true,
    val loves: Boolean = true,
    // Start and end of a maratona I joined (phase 7).
    val maratonas: Boolean = true,
    // Debate day of a CineClube I joined (phase 8).
    val cineclubes: Boolean = true
)

/** A device that receives push notifications. [locale] picks the language of the texts. */
data class DeviceToken(val platform: String, val token: String, val locale: String?)

/** Platforms a push token can come from. */
object DevicePlatforms {
    val ALL = setOf("android", "ios", "web")
}

/**
 * Something that happened to a user and may reach them by WebSocket and push. [data] goes
 * to the app as is (strings only, like WsMessage) so a tap can open the right screen.
 */
sealed class SocialEvent(val type: String) {
    abstract val data: Map<String, String>

    data class NewFollower(val follower: UserSummary) : SocialEvent("new_follower") {
        override val data get() = mapOf("userId" to follower.id, "name" to follower.nameToShow)
    }

    data class ActivityLoved(val activityId: Long, val by: UserSummary) : SocialEvent("activity_loved") {
        override val data get() = mapOf("activityId" to activityId.toString(), "userId" to by.id, "name" to by.nameToShow)
    }

    data class MessageReceived(val message: Message, val sender: UserSummary?) : SocialEvent("message") {
        override val data
            get() = mapOf(
                "conversationId" to message.conversationId.toString(),
                "messageId" to message.id.toString(),
                "senderId" to (message.senderId ?: ""),
                "name" to (sender?.nameToShow ?: ""),
                "body" to message.body,
                "system" to message.system.toString(),
                "createdAt" to message.createdAt.toString()
            )
    }

    /** The sender's own message, for their other open sessions; never pushed. */
    data class MessageSent(val message: Message) : SocialEvent("message") {
        override val data get() = MessageReceived(message, null).data
    }

    data class UnreadChanged(val count: UnreadCount) : SocialEvent("unread") {
        override val data
            get() = mapOf("conversations" to count.conversations.toString(), "messages" to count.messages.toString())
    }

    /** Something changed in a watch party the user is in (F10.4); WebSocket only, never pushed. */
    data class WatchPartyChanged(val code: String, val event: String) : SocialEvent("watch_party") {
        override val data get() = mapOf("code" to code, "event" to event)
    }

    /** Debate day of a CineClube session the user takes part in (F8, B8). */
    data class CineClubeDebate(val slug: String, val title: String, val movieTitle: String) : SocialEvent("cineclube_debate") {
        override val data get() = mapOf("slug" to slug, "title" to title, "movie" to movieTitle)
    }

    /** A maratona the user joined started (B15.1). */
    data class MaratonaStarted(val slug: String, val title: String) : SocialEvent("maratona_started") {
        override val data get() = mapOf("slug" to slug, "title" to title)
    }

    /** A maratona the user joined finished (B15.1). */
    data class MaratonaFinished(val slug: String, val title: String) : SocialEvent("maratona_finished") {
        override val data get() = mapOf("slug" to slug, "title" to title)
    }

    /**
     * An import of the user stopped running (B15.2): read and waiting for the preview to be
     * confirmed, written, or failed. [watched] is the number of watched movies read or written.
     */
    data class ImportUpdated(val jobId: Long, val source: String, val status: String, val watched: Int) :
        SocialEvent("import_updated") {
        override val data
            get() = mapOf("jobId" to jobId.toString(), "source" to source, "status" to status, "watched" to watched.toString())
    }
}

/** The name shown in notifications: the display name, else the @handle, else the username. */
val UserSummary.nameToShow: String
    get() = displayName?.takeIf { it.isNotBlank() } ?: handle?.let { "@$it" } ?: username
