package org.mobyle.domain.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * A Sussurro (F6.5). [senderId] is null for system messages, whose [body] is a code the app
 * translates (see [SystemMessage]). [mine] is about the viewer.
 */
@Serializable
data class Message(
    val id: Long,
    val conversationId: Long,
    val senderId: String?,
    val body: String,
    val system: Boolean,
    val mine: Boolean,
    val createdAt: Instant
)

/** Codes of the system messages. */
object SystemMessage {
    // One of the two stopped following the other: the conversation is read-only.
    const val MUTUAL_LOST = "mutual_lost"
    // They follow each other again.
    const val MUTUAL_RESTORED = "mutual_restored"
}

/**
 * A conversation as one of its two members sees it. [enabled] is true while both follow each
 * other: only then can they send messages. [unread] counts messages from the other one (and
 * system messages) after the viewer's last read.
 */
@Serializable
data class Conversation(
    val id: Long,
    val other: UserSummary,
    val enabled: Boolean,
    val lastMessage: Message?,
    val unread: Int,
    val lastMessageAt: Instant
)

/** The badge of the Sussurros (F6.6): conversations with something unread, and messages in total. */
@Serializable
data class UnreadCount(val conversations: Int, val messages: Int)
