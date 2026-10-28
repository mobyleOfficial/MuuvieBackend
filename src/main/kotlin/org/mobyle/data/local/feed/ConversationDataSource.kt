package org.mobyle.data.local.feed

import kotlinx.datetime.Clock
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.alias
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.upsert
import org.mobyle.data.local.database.ConversationReadsTable
import org.mobyle.data.local.database.ConversationsTable
import org.mobyle.data.local.database.MessagesTable
import org.mobyle.data.local.database.UserFollowsTable
import org.mobyle.data.local.database.UsersTable
import org.mobyle.data.local.database.userDbIdOf
import org.mobyle.domain.model.Conversation
import org.mobyle.domain.model.Cursor
import org.mobyle.domain.model.FeedCursor
import org.mobyle.domain.model.Message
import org.mobyle.domain.model.UnreadCount
import org.mobyle.domain.model.UserSummary
import org.mobyle.domain.repository.ConversationRepository

/** Exposed implementation of the Sussurros; same contract as [ConversationRepository]. */
interface ConversationDataSource : ConversationRepository

class ConversationDataSourceImpl : ConversationDataSource {

    override fun findConversation(userId: String, otherId: String): Long? = transaction {
        val (a, b) = pair(userId, otherId) ?: return@transaction null
        ConversationsTable.select(ConversationsTable.id)
            .where { (ConversationsTable.userA eq a) and (ConversationsTable.userB eq b) }
            .firstOrNull()
            ?.get(ConversationsTable.id)?.value
    }

    override fun getOrCreateConversation(userId: String, otherId: String): Long = transaction {
        val (a, b) = pair(userId, otherId) ?: throw IllegalArgumentException("Unknown user")
        ConversationsTable.insertIgnore {
            it[userA] = a
            it[userB] = b
        }
        ConversationsTable.select(ConversationsTable.id)
            .where { (ConversationsTable.userA eq a) and (ConversationsTable.userB eq b) }
            .first()[ConversationsTable.id].value
    }

    override fun members(conversationId: Long): Pair<String, String>? = transaction {
        val row = ConversationsTable.selectAll().where { ConversationsTable.id eq conversationId }.firstOrNull()
            ?: return@transaction null
        val ids = UsersTable.select(UsersTable.id, UsersTable.externalId)
            .where { UsersTable.id inList listOf(row[ConversationsTable.userA].value, row[ConversationsTable.userB].value) }
            .associate { it[UsersTable.id].value to it[UsersTable.externalId] }
        ids[row[ConversationsTable.userA].value]!! to ids[row[ConversationsTable.userB].value]!!
    }

    override fun conversation(viewerId: String, conversationId: Long): Conversation? = transaction {
        val viewer = userDbIdOf(viewerId) ?: return@transaction null
        conversationsOf(viewer, ConversationsTable.id eq conversationId, limit = 1).firstOrNull()
    }

    override fun conversations(viewerId: String, cursor: FeedCursor?, limit: Int): Cursor<Conversation> = transaction {
        val viewer = userDbIdOf(viewerId) ?: return@transaction Cursor(emptyList(), null)
        val after = cursor?.let {
            (ConversationsTable.lastMessageAt less it.at) or
                ((ConversationsTable.lastMessageAt eq it.at) and (ConversationsTable.id less it.id))
        } ?: Op.TRUE
        val rows = conversationsOf(viewer, after, limit + 1)
        val page = rows.take(limit)
        val next = if (rows.size > limit) page.last().let { FeedCursor(it.lastMessageAt, it.id).encode() } else null
        Cursor(page, next)
    }

    override fun messages(viewerId: String, conversationId: Long, cursor: FeedCursor?, limit: Int): Cursor<Message> =
        transaction {
            val viewer = userDbIdOf(viewerId) ?: return@transaction Cursor(emptyList(), null)
            val before = cursor?.let { MessagesTable.id less it.id } ?: Op.TRUE
            val rows = MessagesTable.join(UsersTable, JoinType.LEFT, MessagesTable.senderId, UsersTable.id)
                .selectAll()
                .where { (MessagesTable.conversationId eq conversationId) and before }
                .orderBy(MessagesTable.id to SortOrder.DESC)
                .limit(limit + 1)
                .toList()
            val page = rows.take(limit).map { it.toMessage(viewer) }
            val next = if (rows.size > limit) page.last().let { FeedCursor(it.createdAt, it.id).encode() } else null
            Cursor(page, next)
        }

    override fun addMessage(conversationId: Long, senderId: String?, body: String): Message = transaction {
        val sender = senderId?.let { userDbIdOf(it) ?: throw IllegalArgumentException("Unknown user $it") }
        val now = Clock.System.now()
        val id = MessagesTable.insertAndGetId {
            it[MessagesTable.conversationId] = conversationId
            it[MessagesTable.senderId] = sender
            it[MessagesTable.body] = body
            it[createdAt] = now
        }.value
        ConversationsTable.update({ ConversationsTable.id eq conversationId }) { it[lastMessageAt] = now }
        // The sender has read everything up to their own message.
        sender?.let { markReadUpTo(conversationId, it, id) }
        Message(id, conversationId, senderId, body, system = senderId == null, mine = senderId != null, createdAt = now)
    }

    override fun markRead(conversationId: Long, userId: String) {
        transaction {
            val user = userDbIdOf(userId) ?: return@transaction
            val last = MessagesTable.select(MessagesTable.id)
                .where { MessagesTable.conversationId eq conversationId }
                .orderBy(MessagesTable.id to SortOrder.DESC)
                .limit(1)
                .firstOrNull()
                ?.get(MessagesTable.id)?.value ?: return@transaction
            markReadUpTo(conversationId, user, last)
        }
    }

    override fun unreadCount(userId: String): UnreadCount = transaction {
        val user = userDbIdOf(userId) ?: return@transaction UnreadCount(0, 0)
        val counts = unreadByConversation(user, Op.TRUE)
        UnreadCount(conversations = counts.count { it.value > 0 }, messages = counts.values.sum())
    }

    // ── Helpers (inside a transaction) ──────────────────────────────────────

    /** The two users.id of a pair, smaller first, or null when one does not exist. */
    private fun pair(userId: String, otherId: String): Pair<Long, Long>? {
        val a = userDbIdOf(userId) ?: return null
        val b = userDbIdOf(otherId) ?: return null
        return if (a < b) a to b else b to a
    }

    private fun conversationsOf(viewer: Long, condition: Op<Boolean>, limit: Int): List<Conversation> {
        val other = UsersTable.alias("other")
        val otherId = other[UsersTable.id]
        val rows = ConversationsTable
            .join(other, JoinType.INNER, additionalConstraint = {
                ((ConversationsTable.userA eq viewer) and (ConversationsTable.userB eq otherId)) or
                    ((ConversationsTable.userB eq viewer) and (ConversationsTable.userA eq otherId))
            })
            .selectAll()
            .where { ((ConversationsTable.userA eq viewer) or (ConversationsTable.userB eq viewer)) and condition }
            .orderBy(ConversationsTable.lastMessageAt to SortOrder.DESC, ConversationsTable.id to SortOrder.DESC)
            .limit(limit)
            .toList()
        if (rows.isEmpty()) return emptyList()
        val ids = rows.map { it[ConversationsTable.id].value }
        val others = rows.map { it[otherId].value }
        val unread = unreadByConversation(viewer, ConversationsTable.id inList ids)
        val mutuals = mutualsOf(viewer, others)
        val lastMessages = lastMessages(ids, viewer)
        return rows.map { row ->
            val id = row[ConversationsTable.id].value
            Conversation(
                id = id,
                other = UserSummary(
                    id = row[other[UsersTable.externalId]],
                    username = row[other[UsersTable.username]],
                    handle = row[other[UsersTable.handle]],
                    displayName = row[other[UsersTable.displayName]],
                    avatarUrl = row[other[UsersTable.avatarUrl]],
                    accentColor = row[other[UsersTable.accentColor]]
                ),
                enabled = row[otherId].value in mutuals,
                lastMessage = lastMessages[id],
                unread = unread[id] ?: 0,
                lastMessageAt = row[ConversationsTable.lastMessageAt]
            )
        }
    }

    /** Messages not sent by [user] after their last read, per conversation of theirs matching [condition]. */
    private fun unreadByConversation(user: Long, condition: Op<Boolean>): Map<Long, Int> {
        val count = MessagesTable.id.count()
        return MessagesTable
            .join(ConversationsTable, JoinType.INNER, MessagesTable.conversationId, ConversationsTable.id)
            .join(ConversationReadsTable, JoinType.LEFT, additionalConstraint = {
                (ConversationReadsTable.conversationId eq ConversationsTable.id) and (ConversationReadsTable.userId eq user)
            })
            .select(MessagesTable.conversationId, count)
            .where {
                ((ConversationsTable.userA eq user) or (ConversationsTable.userB eq user)) and condition and
                    (MessagesTable.senderId.isNull() or (MessagesTable.senderId neq user)) and
                    (ConversationReadsTable.lastReadMessageId.isNull() or (MessagesTable.id greater ConversationReadsTable.lastReadMessageId))
            }
            .groupBy(MessagesTable.conversationId)
            .associate { it[MessagesTable.conversationId].value to it[count].toInt() }
    }

    /** The latest message of each conversation: one indexed lookup each, for a page of conversations. */
    private fun lastMessages(conversationIds: List<Long>, viewer: Long): Map<Long, Message> =
        conversationIds.mapNotNull { conversationId ->
            MessagesTable.join(UsersTable, JoinType.LEFT, MessagesTable.senderId, UsersTable.id)
                .selectAll()
                .where { MessagesTable.conversationId eq conversationId }
                .orderBy(MessagesTable.id to SortOrder.DESC)
                .limit(1)
                .firstOrNull()
                ?.toMessage(viewer)
        }.associateBy { it.conversationId }

    /** Which of [users] follow [viewer] and are followed by them. */
    private fun mutualsOf(viewer: Long, users: List<Long>): Set<Long> {
        val followed = UserFollowsTable.select(UserFollowsTable.followedId)
            .where { (UserFollowsTable.followerId eq viewer) and (UserFollowsTable.followedId inList users) }
            .map { it[UserFollowsTable.followedId].value }
            .toSet()
        val followers = UserFollowsTable.select(UserFollowsTable.followerId)
            .where { (UserFollowsTable.followedId eq viewer) and (UserFollowsTable.followerId inList users) }
            .map { it[UserFollowsTable.followerId].value }
            .toSet()
        return followed intersect followers
    }

    private fun markReadUpTo(conversationId: Long, user: Long, messageId: Long) {
        ConversationReadsTable.upsert {
            it[ConversationReadsTable.conversationId] = conversationId
            it[userId] = user
            it[lastReadMessageId] = messageId
        }
    }

    /** A message row joined (LEFT) with its sender. */
    private fun ResultRow.toMessage(viewer: Long): Message {
        val sender = this[MessagesTable.senderId]?.value
        return Message(
            id = this[MessagesTable.id].value,
            conversationId = this[MessagesTable.conversationId].value,
            senderId = sender?.let { this[UsersTable.externalId] },
            body = this[MessagesTable.body],
            system = sender == null,
            mine = sender == viewer,
            createdAt = this[MessagesTable.createdAt]
        )
    }
}
