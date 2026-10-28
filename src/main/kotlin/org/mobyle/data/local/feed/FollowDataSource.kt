package org.mobyle.data.local.feed

import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.dao.id.EntityID
import kotlinx.datetime.Clock
import org.mobyle.data.local.database.UserFollowsTable
import org.mobyle.data.local.database.UsersTable
import org.mobyle.data.local.database.userDbIdOf
import org.mobyle.domain.model.FollowEdge
import org.mobyle.domain.model.FollowStatus
import org.mobyle.domain.model.Page
import org.mobyle.domain.model.PageRequest
import org.mobyle.domain.model.UserSummary
import org.mobyle.domain.repository.FollowRepository

/** Exposed implementation of the follows; same contract as [FollowRepository]. */
interface FollowDataSource : FollowRepository

class FollowDataSourceImpl : FollowDataSource {

    override fun userSummary(userId: String): UserSummary? = transaction {
        UsersTable.selectAll().where { UsersTable.externalId eq userId }.firstOrNull()?.toUserSummary()
    }

    override fun status(viewerId: String, otherId: String): FollowStatus =
        statuses(viewerId, listOf(otherId))[otherId] ?: FollowStatus.of(following = false, followedBy = false)

    override fun statuses(viewerId: String, userIds: Collection<String>): Map<String, FollowStatus> = transaction {
        val viewer = userDbIdOf(viewerId) ?: return@transaction emptyMap()
        val ids = UsersTable.select(UsersTable.id, UsersTable.externalId)
            .where { UsersTable.externalId inList userIds }
            .associate { it[UsersTable.id].value to it[UsersTable.externalId] }
        if (ids.isEmpty()) return@transaction emptyMap()
        val following = UserFollowsTable.select(UserFollowsTable.followedId)
            .where { (UserFollowsTable.followerId eq viewer) and (UserFollowsTable.followedId inList ids.keys) }
            .map { it[UserFollowsTable.followedId].value }
            .toSet()
        val followedBy = UserFollowsTable.select(UserFollowsTable.followerId)
            .where { (UserFollowsTable.followedId eq viewer) and (UserFollowsTable.followerId inList ids.keys) }
            .map { it[UserFollowsTable.followerId].value }
            .toSet()
        ids.entries.associate { (id, externalId) -> externalId to FollowStatus.of(id in following, id in followedBy) }
    }

    override fun follow(followerId: String, followedId: String): Boolean = transaction {
        val follower = userDbIdOf(followerId) ?: return@transaction false
        val followed = userDbIdOf(followedId) ?: return@transaction false
        UserFollowsTable.insertIgnore {
            it[UserFollowsTable.followerId] = follower
            it[UserFollowsTable.followedId] = followed
            it[createdAt] = Clock.System.now()
        }.insertedCount > 0
    }

    override fun unfollow(followerId: String, followedId: String): Boolean = transaction {
        val follower = userDbIdOf(followerId) ?: return@transaction false
        val followed = userDbIdOf(followedId) ?: return@transaction false
        UserFollowsTable.deleteWhere {
            (UserFollowsTable.followerId eq follower) and (UserFollowsTable.followedId eq followed)
        } > 0
    }

    override fun followers(userId: String, page: PageRequest): Page<FollowEdge> =
        edges(userId, page, ofUser = UserFollowsTable.followedId, other = UserFollowsTable.followerId)

    override fun following(userId: String, page: PageRequest): Page<FollowEdge> =
        edges(userId, page, ofUser = UserFollowsTable.followerId, other = UserFollowsTable.followedId)

    /** Follows where [ofUser] is [userId], listing the user on the [other] side, newest first. */
    private fun edges(
        userId: String,
        page: PageRequest,
        ofUser: Column<EntityID<Long>>,
        other: Column<EntityID<Long>>
    ): Page<FollowEdge> = transaction {
        val user = userDbIdOf(userId) ?: return@transaction Page.of(emptyList(), page, 0)
        val query = UserFollowsTable.join(UsersTable, JoinType.INNER, other, UsersTable.id)
            .selectAll()
            .where { ofUser eq user }
        val total = query.count().toInt()
        val results = query
            .orderBy(UserFollowsTable.createdAt to SortOrder.DESC, UserFollowsTable.id to SortOrder.DESC)
            .limit(page.limit, offset = page.offset)
            .map { FollowEdge(it.toUserSummary(), it[UserFollowsTable.createdAt]) }
        Page.of(results, page, total)
    }
}
