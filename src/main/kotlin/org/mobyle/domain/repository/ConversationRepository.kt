package org.mobyle.domain.repository

import org.mobyle.domain.model.Conversation
import org.mobyle.domain.model.Cursor
import org.mobyle.domain.model.FeedCursor
import org.mobyle.domain.model.Message
import org.mobyle.domain.model.UnreadCount

/** The Sussurros (F6.5). A conversation is enabled while its two members are mutuals. */
interface ConversationRepository {
    fun findConversation(userId: String, otherId: String): Long?
    fun getOrCreateConversation(userId: String, otherId: String): Long
    /** The external ids of the two members. */
    fun members(conversationId: Long): Pair<String, String>?
    fun conversation(viewerId: String, conversationId: Long): Conversation?
    /** The viewer's conversations, latest message first. */
    fun conversations(viewerId: String, cursor: FeedCursor?, limit: Int): Cursor<Conversation>
    /** Messages, newest first. */
    fun messages(viewerId: String, conversationId: Long, cursor: FeedCursor?, limit: Int): Cursor<Message>
    /** Adds a message; a null [senderId] makes it a system message. */
    fun addMessage(conversationId: Long, senderId: String?, body: String): Message
    fun markRead(conversationId: Long, userId: String)
    fun unreadCount(userId: String): UnreadCount
}
