package org.mobyle.domain.usecase.messages

import org.mobyle.domain.model.Conversation
import org.mobyle.domain.model.Cursor
import org.mobyle.domain.model.FeedCursor
import org.mobyle.domain.model.ForbiddenException
import org.mobyle.domain.model.Message
import org.mobyle.domain.model.NotFoundException
import org.mobyle.domain.model.SocialEvent
import org.mobyle.domain.model.TooManyRequestsException
import org.mobyle.domain.model.UnreadCount
import org.mobyle.domain.model.ValidationException
import org.mobyle.domain.repository.ConversationRepository
import org.mobyle.domain.repository.FollowRepository
import org.mobyle.domain.repository.RateLimiter
import org.mobyle.domain.repository.SocialNotifier

private const val PAGE_SIZE = 30
private const val MAX_PAGE_SIZE = 50
const val MESSAGE_MAX_LENGTH = 2000

/** B6 rule: at most 30 messages per minute per user. */
private const val MESSAGES_PER_MINUTE = 30
private const val MINUTE_MS = 60_000L

private fun pageLimit(limit: Int?): Int {
    val value = limit ?: PAGE_SIZE
    if (value !in 1..MAX_PAGE_SIZE) throw ValidationException("limit", "limit must be between 1 and $MAX_PAGE_SIZE")
    return value
}

/** Only the two members of a conversation know it exists. */
class ConversationAccess(private val repository: ConversationRepository) {
    fun memberConversation(userId: String, conversationId: Long): Conversation =
        repository.conversation(userId, conversationId)?.takeIf { it.other.id != userId }
            ?: throw NotFoundException("Conversation not found")
}

/** The Sussurros list (F6.5), latest message first. */
class GetConversations(private val repository: ConversationRepository) {
    operator fun invoke(userId: String, cursor: String?, limit: Int?): Cursor<Conversation> =
        repository.conversations(userId, FeedCursor.decode(cursor), pageLimit(limit))
}

class GetConversation(private val access: ConversationAccess) {
    operator fun invoke(userId: String, conversationId: Long): Conversation = access.memberConversation(userId, conversationId)
}

/** The conversation with another user, to open it from their profile; 404 until the first message. */
class GetConversationWith(private val repository: ConversationRepository) {
    operator fun invoke(userId: String, otherId: String): Conversation {
        val id = repository.findConversation(userId, otherId) ?: throw NotFoundException("Conversation not found")
        return repository.conversation(userId, id) ?: throw NotFoundException("Conversation not found")
    }
}

class GetMessages(private val repository: ConversationRepository, private val access: ConversationAccess) {
    operator fun invoke(userId: String, conversationId: Long, cursor: String?, limit: Int?): Cursor<Message> {
        access.memberConversation(userId, conversationId)
        return repository.messages(userId, conversationId, FeedCursor.decode(cursor), pageLimit(limit))
    }
}

/**
 * Sends a Sussurro (F6.5), creating the conversation on the first one. Only between mutuals.
 * The recipient gets it in real time (and by push); the sender's other sessions too.
 */
class SendMessage(
    private val repository: ConversationRepository,
    private val follows: FollowRepository,
    private val notifier: SocialNotifier,
    private val rateLimiter: RateLimiter
) {
    operator fun invoke(userId: String, recipientId: String, body: String): Message {
        val text = body.trim()
        if (text.isEmpty() || text.length > MESSAGE_MAX_LENGTH) {
            throw ValidationException("body", "body must have between 1 and $MESSAGE_MAX_LENGTH characters")
        }
        if (recipientId == userId) throw ValidationException("userId", "Cannot message yourself")
        follows.userSummary(recipientId) ?: throw NotFoundException("User not found")
        if (!follows.status(userId, recipientId).mutual) {
            throw ForbiddenException("not_mutual", "Messages are only between users who follow each other")
        }
        if (!rateLimiter.tryAcquire("message:$userId", MESSAGES_PER_MINUTE, MINUTE_MS)) {
            throw TooManyRequestsException("rate_limited", "Too many messages, try again in a minute")
        }
        val conversationId = repository.getOrCreateConversation(userId, recipientId)
        val message = repository.addMessage(conversationId, userId, text)
        notifier.notify(recipientId, SocialEvent.MessageReceived(message.copy(mine = false), follows.userSummary(userId)))
        notifier.notify(recipientId, SocialEvent.UnreadChanged(repository.unreadCount(recipientId)))
        notifier.notify(userId, SocialEvent.MessageSent(message))
        return message
    }
}

/** Marks everything in the conversation as read; the badge of the user's other sessions follows. */
class MarkConversationRead(
    private val repository: ConversationRepository,
    private val access: ConversationAccess,
    private val notifier: SocialNotifier
) {
    operator fun invoke(userId: String, conversationId: Long): UnreadCount {
        access.memberConversation(userId, conversationId)
        repository.markRead(conversationId, userId)
        val count = repository.unreadCount(userId)
        notifier.notify(userId, SocialEvent.UnreadChanged(count))
        return count
    }
}

/** The badge of the Sussurros (F6.6). */
class GetUnreadCount(private val repository: ConversationRepository) {
    operator fun invoke(userId: String): UnreadCount = repository.unreadCount(userId)
}
