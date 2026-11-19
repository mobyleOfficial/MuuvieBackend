package org.mobyle.data.local.moderation

import kotlinx.datetime.Clock
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.exists
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.not
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.mobyle.data.local.database.ActivitiesTable
import org.mobyle.data.local.database.CineClubesTable
import org.mobyle.data.local.database.CommentsTable
import org.mobyle.data.local.database.LocadorasTable
import org.mobyle.data.local.database.MaratonasTable
import org.mobyle.data.local.database.MessagesTable
import org.mobyle.data.local.database.ReportsTable
import org.mobyle.data.local.database.ShelvesTable
import org.mobyle.data.local.database.UserBlocksTable
import org.mobyle.data.local.database.UserFollowsTable
import org.mobyle.data.local.database.UsersTable
import org.mobyle.data.local.database.userDbIdOf
import org.mobyle.data.local.database.WatchLogsTable
import org.mobyle.data.local.feed.toUserSummary
import org.mobyle.domain.model.BlockedUser
import org.mobyle.domain.model.Page
import org.mobyle.domain.model.PageRequest
import org.mobyle.domain.model.ReportDraft
import org.mobyle.domain.model.ReportTarget
import org.mobyle.domain.repository.ModerationRepository

/** Exposed implementation of blocks and reports; same contract as [ModerationRepository]. */
interface ModerationDataSource : ModerationRepository

class ModerationDataSourceImpl : ModerationDataSource {

    override fun block(blockerId: String, blockedId: String): Boolean = transaction {
        val blocker = userDbIdOf(blockerId) ?: return@transaction false
        val blocked = userDbIdOf(blockedId) ?: return@transaction false
        val inserted = UserBlocksTable.insertIgnore {
            it[UserBlocksTable.blockerId] = blocker
            it[UserBlocksTable.blockedId] = blocked
            it[createdAt] = Clock.System.now()
        }.insertedCount > 0
        UserFollowsTable.deleteWhere {
            ((followerId eq blocker) and (followedId eq blocked)) or ((followerId eq blocked) and (followedId eq blocker))
        }
        inserted
    }

    override fun unblock(blockerId: String, blockedId: String): Boolean = transaction {
        val blocker = userDbIdOf(blockerId) ?: return@transaction false
        val blocked = userDbIdOf(blockedId) ?: return@transaction false
        UserBlocksTable.deleteWhere {
            (UserBlocksTable.blockerId eq blocker) and (UserBlocksTable.blockedId eq blocked)
        } > 0
    }

    override fun blocks(blockerId: String, blockedId: String): Boolean = transaction {
        val blocker = userDbIdOf(blockerId) ?: return@transaction false
        val blocked = userDbIdOf(blockedId) ?: return@transaction false
        !UserBlocksTable.selectAll()
            .where { (UserBlocksTable.blockerId eq blocker) and (UserBlocksTable.blockedId eq blocked) }
            .empty()
    }

    override fun blockedEitherWay(userId: String, otherId: String): Boolean = transaction {
        val user = userDbIdOf(userId) ?: return@transaction false
        val other = userDbIdOf(otherId) ?: return@transaction false
        !UserBlocksTable.selectAll()
            .where {
                ((UserBlocksTable.blockerId eq user) and (UserBlocksTable.blockedId eq other)) or
                    ((UserBlocksTable.blockerId eq other) and (UserBlocksTable.blockedId eq user))
            }
            .empty()
    }

    override fun blockedIds(userId: String): Set<String> = transaction {
        val user = userDbIdOf(userId) ?: return@transaction emptySet()
        val blocked = UserBlocksTable.join(UsersTable, JoinType.INNER, UserBlocksTable.blockedId, UsersTable.id)
            .select(UsersTable.externalId)
            .where { UserBlocksTable.blockerId eq user }
            .map { it[UsersTable.externalId] }
        val blockers = UserBlocksTable.join(UsersTable, JoinType.INNER, UserBlocksTable.blockerId, UsersTable.id)
            .select(UsersTable.externalId)
            .where { UserBlocksTable.blockedId eq user }
            .map { it[UsersTable.externalId] }
        (blocked + blockers).toSet()
    }

    override fun blockedUsers(userId: String, page: PageRequest): Page<BlockedUser> = transaction {
        val user = userDbIdOf(userId) ?: return@transaction Page.of(emptyList(), page, 0)
        val query = UserBlocksTable.join(UsersTable, JoinType.INNER, UserBlocksTable.blockedId, UsersTable.id)
            .selectAll()
            .where { UserBlocksTable.blockerId eq user }
        val total = query.count().toInt()
        val results = query
            .orderBy(UserBlocksTable.createdAt to SortOrder.DESC)
            .limit(page.limit, offset = page.offset)
            .map { BlockedUser(it.toUserSummary(), it[UserBlocksTable.createdAt]) }
        Page.of(results, page, total)
    }

    override fun report(draft: ReportDraft): Long = transaction {
        ReportsTable.insertAndGetId {
            it[reporterId] = userDbIdOf(draft.reporterId)
            it[reportedUserId] = draft.reportedUserId?.let { id -> userDbIdOf(id) }
            it[targetType] = draft.target.wireValue
            it[targetId] = draft.targetId
            it[reason] = draft.reason.wireValue
            it[details] = draft.details
            it[createdAt] = Clock.System.now()
        }.value
    }

    override fun ownerOf(target: ReportTarget, targetId: String): String? = transaction {
        val numericId = targetId.toLongOrNull()
        when (target) {
            ReportTarget.USER -> UsersTable.select(UsersTable.externalId)
                .where {
                    if (targetId.startsWith("@")) UsersTable.handle eq targetId.removePrefix("@").lowercase()
                    else UsersTable.externalId eq targetId
                }
                .firstOrNull()?.get(UsersTable.externalId)
            ReportTarget.ACTIVITY -> numericId?.let { ownerBy(ActivitiesTable, ActivitiesTable.actorId, it) }
            ReportTarget.COMMENT -> numericId?.let { ownerBy(CommentsTable, CommentsTable.userId, it) }
            ReportTarget.SHELF -> numericId?.let { ownerBy(ShelvesTable, ShelvesTable.userId, it) }
            ReportTarget.LOCADORA -> numericId?.let { ownerBy(LocadorasTable, LocadorasTable.userId, it) }
            ReportTarget.WATCH_LOG -> numericId?.let { ownerBy(WatchLogsTable, WatchLogsTable.userId, it) }
            ReportTarget.MESSAGE -> numericId?.let { id ->
                MessagesTable.join(UsersTable, JoinType.INNER, MessagesTable.senderId, UsersTable.id)
                    .select(UsersTable.externalId)
                    .where { MessagesTable.id eq id }
                    .firstOrNull()?.get(UsersTable.externalId)
            }
            ReportTarget.MARATONA -> MaratonasTable.join(UsersTable, JoinType.INNER, MaratonasTable.ownerId, UsersTable.id)
                .select(UsersTable.externalId)
                .where { if (numericId != null) MaratonasTable.id eq numericId else MaratonasTable.slug eq targetId }
                .firstOrNull()?.get(UsersTable.externalId)
            ReportTarget.CINECLUBE -> CineClubesTable.join(UsersTable, JoinType.INNER, CineClubesTable.ownerId, UsersTable.id)
                .select(UsersTable.externalId)
                .where { if (numericId != null) CineClubesTable.id eq numericId else CineClubesTable.slug eq targetId }
                .firstOrNull()?.get(UsersTable.externalId)
        }
    }

    /** The external id of the user in [ownerColumn] of the row [id] of [table]. Inside a transaction. */
    private fun ownerBy(table: LongIdTable, ownerColumn: Column<EntityID<Long>>, id: Long): String? =
        table.join(UsersTable, JoinType.INNER, ownerColumn, UsersTable.id)
            .select(UsersTable.externalId)
            .where { table.id eq id }
            .firstOrNull()?.get(UsersTable.externalId)
}

/**
 * Rows whose [userColumn] is someone who blocks [viewer] or whom [viewer] blocks are left out.
 * For queries of other users' content; must be used inside a transaction.
 */
fun notBlockedWith(viewer: Long, userColumn: Column<EntityID<Long>>): Op<Boolean> = not(
    exists(
        UserBlocksTable.select(UserBlocksTable.blockerId).where {
            ((UserBlocksTable.blockerId eq viewer) and (UserBlocksTable.blockedId eq userColumn)) or
                ((UserBlocksTable.blockedId eq viewer) and (UserBlocksTable.blockerId eq userColumn))
        }
    )
)
