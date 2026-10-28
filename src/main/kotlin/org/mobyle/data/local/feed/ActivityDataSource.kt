package org.mobyle.data.local.feed

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.Query
import org.jetbrains.exposed.sql.QueryBuilder
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.TextColumnType
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inSubQuery
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.exists
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.mobyle.data.local.database.ActivitiesTable
import org.mobyle.data.local.database.ActivityLovesTable
import org.mobyle.data.local.database.ShelvesTable
import org.mobyle.data.local.database.UserFollowsTable
import org.mobyle.data.local.database.UsersTable
import org.mobyle.data.local.database.userDbIdOf
import org.mobyle.domain.model.vocabulary.SystemShelfType
import org.mobyle.domain.model.Activity
import org.mobyle.domain.model.ActivityDraft
import org.mobyle.domain.model.ActivityLoves
import org.mobyle.domain.model.ActivityRecord
import org.mobyle.domain.model.Cursor
import org.mobyle.domain.model.FeedCursor
import org.mobyle.domain.model.UserSummary
import org.mobyle.domain.model.vocabulary.ActivitySubject
import org.mobyle.domain.model.vocabulary.ActivityType
import org.mobyle.domain.model.vocabulary.FeedScope
import org.mobyle.domain.model.vocabulary.MovieActivityKind
import org.mobyle.domain.model.vocabulary.Visibility
import org.mobyle.domain.policy.ViewerRelation
import org.mobyle.domain.policy.VisibilityPolicy
import org.mobyle.domain.repository.ActivityRepository

/** Exposed implementation of the feed; same contract as [ActivityRepository]. */
interface ActivityDataSource : ActivityRepository

class ActivityDataSourceImpl : ActivityDataSource {

    override fun insert(draft: ActivityDraft): Long = transaction {
        val actor = userDbIdOf(draft.actorId) ?: throw IllegalArgumentException("Unknown user ${draft.actorId}")
        ActivitiesTable.insertAndGetId {
            it[actorId] = actor
            it[type] = draft.type.wireValue
            it[visibility] = draft.visibility.wireValue
            it[subjectType] = draft.subjectType.wireValue
            it[subjectId] = draft.subjectId
            it[movieId] = draft.movieId
            it[payload] = draft.payload.toString()
        }.value
    }

    override fun update(id: Long, type: ActivityType, visibility: Visibility, payload: JsonObject, touch: Boolean) {
        transaction {
            val now = Clock.System.now()
            ActivitiesTable.update({ ActivitiesTable.id eq id }) {
                it[ActivitiesTable.type] = type.wireValue
                it[ActivitiesTable.visibility] = visibility.wireValue
                it[ActivitiesTable.payload] = payload.toString()
                it[updatedAt] = now
                if (touch) it[createdAt] = now
            }
        }
    }

    override fun delete(id: Long) {
        transaction { ActivitiesTable.deleteWhere { ActivitiesTable.id eq id } }
    }

    override fun getRecord(id: Long): ActivityRecord? = transaction {
        (ActivitiesTable innerJoin UsersTable).selectAll()
            .where { ActivitiesTable.id eq id }
            .firstOrNull()
            ?.toRecord()
    }

    override fun findRecent(
        actorId: String,
        subjectType: ActivitySubject,
        subjectId: Long,
        types: Set<ActivityType>,
        since: Instant
    ): ActivityRecord? = transaction {
        val actor = userDbIdOf(actorId) ?: return@transaction null
        (ActivitiesTable innerJoin UsersTable).selectAll()
            .where {
                (ActivitiesTable.actorId eq actor) and (ActivitiesTable.subjectType eq subjectType.wireValue) and
                    (ActivitiesTable.subjectId eq subjectId) and (ActivitiesTable.type inList types.map { it.wireValue }) and
                    (ActivitiesTable.createdAt greater since)
            }
            .orderBy(ActivitiesTable.createdAt to SortOrder.DESC, ActivitiesTable.id to SortOrder.DESC)
            .limit(1)
            .firstOrNull()
            ?.toRecord()
    }

    override fun findBySubject(actorId: String, subjectType: ActivitySubject, subjectId: Long): List<ActivityRecord> =
        transaction {
            val actor = userDbIdOf(actorId) ?: return@transaction emptyList()
            (ActivitiesTable innerJoin UsersTable).selectAll()
                .where {
                    (ActivitiesTable.actorId eq actor) and (ActivitiesTable.subjectType eq subjectType.wireValue) and
                        (ActivitiesTable.subjectId eq subjectId)
                }
                .map { it.toRecord() }
        }

    override fun feed(viewerId: String, scope: FeedScope, cursor: FeedCursor?, limit: Int): Cursor<Activity> = transaction {
        val viewer = userDbIdOf(viewerId) ?: return@transaction Cursor(emptyList(), null)
        val mine = ActivitiesTable.actorId eq viewer
        val condition = when (scope) {
            // "Só eu" also lists what I hid, so I can show it again.
            FeedScope.ME -> mine
            FeedScope.FOLLOWING -> fromFollowed(viewer)
            FeedScope.ALL -> (mine and (ActivitiesTable.hidden eq false)) or fromFollowed(viewer)
        }
        page(viewer, condition, cursor, limit)
    }

    override fun userActivities(
        viewerId: String?,
        ownerId: String,
        visibilities: Set<Visibility>,
        includeHidden: Boolean,
        cursor: FeedCursor?,
        limit: Int
    ): Cursor<Activity> = transaction {
        val owner = userDbIdOf(ownerId) ?: return@transaction Cursor(emptyList(), null)
        val viewer = viewerId?.let { userDbIdOf(it) }
        var condition = (ActivitiesTable.actorId eq owner) and
            (ActivitiesTable.visibility inList visibilities.map { it.wireValue })
        if (!includeHidden) condition = condition and (ActivitiesTable.hidden eq false)
        page(viewer, condition, cursor, limit)
    }

    override fun movieActivities(
        viewerId: String?,
        movieId: Long,
        scope: FeedScope,
        kind: MovieActivityKind?,
        cursor: FeedCursor?,
        limit: Int
    ): Cursor<Activity> = transaction {
        val viewer = viewerId?.let { userDbIdOf(it) }
        val publicOnes = (ActivitiesTable.visibility eq Visibility.PUBLIC.wireValue) and (ActivitiesTable.hidden eq false)
        val visible = when {
            viewer == null -> if (scope == FeedScope.ALL) publicOnes else return@transaction Cursor(emptyList(), null)
            scope == FeedScope.FOLLOWING -> fromFollowed(viewer)
            scope == FeedScope.ME -> ActivitiesTable.actorId eq viewer
            else -> publicOnes or fromFollowed(viewer) or ((ActivitiesTable.actorId eq viewer) and (ActivitiesTable.hidden eq false))
        }
        val ofKind = when (kind) {
            null -> Op.TRUE
            MovieActivityKind.RATINGS -> JsonbHasKey(ActivitiesTable.payload, "rating")
            MovieActivityKind.UPDATES -> ActivitiesTable.type inList listOf(
                ActivityType.WATCH_SESSION, ActivityType.FINISHED_MOVIE, ActivityType.WATCHING_NOW
            ).map { it.wireValue }
        }
        page(viewer, (ActivitiesTable.movieId eq movieId) and visible and ofKind, cursor, limit)
    }

    override fun subjectActivities(
        viewerId: String?,
        subjectType: ActivitySubject,
        subjectId: Long,
        cursor: FeedCursor?,
        limit: Int
    ): Cursor<Activity> = transaction {
        val viewer = viewerId?.let { userDbIdOf(it) }
        val condition = (ActivitiesTable.subjectType eq subjectType.wireValue) and (ActivitiesTable.subjectId eq subjectId) and
            (ActivitiesTable.visibility eq Visibility.PUBLIC.wireValue) and (ActivitiesTable.hidden eq false)
        page(viewer, condition, cursor, limit)
    }

    override fun setHidden(id: Long, hidden: Boolean) {
        transaction {
            ActivitiesTable.update({ ActivitiesTable.id eq id }) { it[ActivitiesTable.hidden] = hidden }
        }
    }

    override fun lovedBy(id: Long, userId: String): Boolean = transaction {
        val user = userDbIdOf(userId) ?: return@transaction false
        !ActivityLovesTable.selectAll()
            .where { (ActivityLovesTable.activityId eq id) and (ActivityLovesTable.userId eq user) }
            .empty()
    }

    override fun love(id: Long, userId: String): ActivityLoves = transaction {
        val user = userDbIdOf(userId) ?: throw IllegalArgumentException("Unknown user $userId")
        ActivityLovesTable.insertIgnore {
            it[activityId] = id
            it[ActivityLovesTable.userId] = user
        }
        loves(id, user)
    }

    override fun unlove(id: Long, userId: String): ActivityLoves = transaction {
        val user = userDbIdOf(userId) ?: throw IllegalArgumentException("Unknown user $userId")
        ActivityLovesTable.deleteWhere { (activityId eq id) and (ActivityLovesTable.userId eq user) }
        loves(id, user)
    }

    override fun watchedVisibility(userId: String): Visibility = transaction {
        val user = userDbIdOf(userId) ?: return@transaction Visibility.DEFAULT
        ShelvesTable.select(ShelvesTable.visibility)
            .where { (ShelvesTable.userId eq user) and (ShelvesTable.systemType eq SystemShelfType.WATCHED.wireValue) }
            .firstOrNull()
            ?.let { Visibility.fromWireValue(it[ShelvesTable.visibility]) }
            ?: Visibility.DEFAULT
    }

    // ── Helpers (inside a transaction) ──────────────────────────────────────

    /**
     * Activities of people [viewer] follows that they may see: VisibilityPolicy for a mutual
     * when the actor follows back, for anyone else otherwise. Hidden ones never show.
     */
    private fun fromFollowed(viewer: Long): Op<Boolean> {
        val followed = ActivitiesTable.actorId inSubQuery
            UserFollowsTable.select(UserFollowsTable.followedId).where { UserFollowsTable.followerId eq viewer }
        val followsBack = exists(
            UserFollowsTable.select(UserFollowsTable.id)
                .where { (UserFollowsTable.followerId eq ActivitiesTable.actorId) and (UserFollowsTable.followedId eq viewer) }
        )
        val forAnyone = VisibilityPolicy.visibleTo(ViewerRelation.OTHER).map { it.wireValue }
        val forMutuals = VisibilityPolicy.visibleTo(ViewerRelation.MUTUAL).map { it.wireValue }
        val visible = (ActivitiesTable.visibility inList forAnyone) or
            ((ActivitiesTable.visibility inList forMutuals) and followsBack)
        return followed and (ActivitiesTable.hidden eq false) and visible
    }

    private fun page(viewer: Long?, condition: Op<Boolean>, cursor: FeedCursor?, limit: Int): Cursor<Activity> {
        val after = cursor?.let {
            (ActivitiesTable.createdAt less it.at) or
                ((ActivitiesTable.createdAt eq it.at) and (ActivitiesTable.id less it.id))
        } ?: Op.TRUE
        val rows = (ActivitiesTable innerJoin UsersTable).selectAll()
            .where { condition and after }
            .orderBy(ActivitiesTable.createdAt to SortOrder.DESC, ActivitiesTable.id to SortOrder.DESC)
            .limit(limit + 1)
            .toList()
        val pageRows = rows.take(limit)
        val activities = toActivities(viewer, pageRows)
        val next = if (rows.size > limit) {
            pageRows.last().let { FeedCursor(it[ActivitiesTable.createdAt], it[ActivitiesTable.id].value).encode() }
        } else null
        return Cursor(activities, next)
    }

    private fun toActivities(viewer: Long?, rows: List<ResultRow>): List<Activity> {
        if (rows.isEmpty()) return emptyList()
        val ids = rows.map { it[ActivitiesTable.id].value }
        val actors = rows.map { it[ActivitiesTable.actorId].value }.distinct()
        val loveCounts = ActivityLovesTable.select(ActivityLovesTable.activityId, ActivityLovesTable.userId.count())
            .where { ActivityLovesTable.activityId inList ids }
            .groupBy(ActivityLovesTable.activityId)
            .associate { it[ActivityLovesTable.activityId].value to it[ActivityLovesTable.userId.count()].toInt() }
        val loved = viewer?.let { v ->
            ActivityLovesTable.select(ActivityLovesTable.activityId)
                .where { (ActivityLovesTable.activityId inList ids) and (ActivityLovesTable.userId eq v) }
                .map { it[ActivityLovesTable.activityId].value }
                .toSet()
        }.orEmpty()
        val mutuals = viewer?.let { mutualsAmong(it, actors) }.orEmpty()
        return rows.map { row ->
            val id = row[ActivitiesTable.id].value
            val actor = row[ActivitiesTable.actorId].value
            Activity(
                id = id,
                type = ActivityType.fromWireValue(row[ActivitiesTable.type])!!,
                actor = row.toUserSummary(),
                visibility = Visibility.fromWireValue(row[ActivitiesTable.visibility]) ?: Visibility.DEFAULT,
                subjectType = ActivitySubject.fromWireValue(row[ActivitiesTable.subjectType])!!,
                subjectId = row[ActivitiesTable.subjectId],
                payload = row.payload(),
                loves = loveCounts[id] ?: 0,
                loved = id in loved,
                self = actor == viewer,
                mutual = actor in mutuals,
                hidden = row[ActivitiesTable.hidden],
                createdAt = row[ActivitiesTable.createdAt]
            )
        }
    }

    /** Which of [users] follow [viewer] and are followed by them. */
    private fun mutualsAmong(viewer: Long, users: List<Long>): Set<Long> {
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

    private fun loves(id: Long, user: Long) = ActivityLoves(
        loves = ActivityLovesTable.selectAll().where { ActivityLovesTable.activityId eq id }.count().toInt(),
        loved = !ActivityLovesTable.selectAll()
            .where { (ActivityLovesTable.activityId eq id) and (ActivityLovesTable.userId eq user) }
            .empty()
    )

    private fun ResultRow.toRecord() = ActivityRecord(
        id = this[ActivitiesTable.id].value,
        actorId = this[UsersTable.externalId],
        type = ActivityType.fromWireValue(this[ActivitiesTable.type])!!,
        visibility = Visibility.fromWireValue(this[ActivitiesTable.visibility]) ?: Visibility.DEFAULT,
        subjectType = ActivitySubject.fromWireValue(this[ActivitiesTable.subjectType])!!,
        subjectId = this[ActivitiesTable.subjectId],
        payload = payload(),
        createdAt = this[ActivitiesTable.createdAt],
        hidden = this[ActivitiesTable.hidden]
    )

    private fun ResultRow.payload(): JsonObject = Json.parseToJsonElement(this[ActivitiesTable.payload]).jsonObject
}

/** The user columns of a row joined with UsersTable. */
internal fun ResultRow.toUserSummary() = UserSummary(
    id = this[UsersTable.externalId],
    username = this[UsersTable.username],
    handle = this[UsersTable.handle],
    displayName = this[UsersTable.displayName],
    avatarUrl = this[UsersTable.avatarUrl],
    accentColor = this[UsersTable.accentColor]
)

/** `jsonb_exists(payload::jsonb, key)`: the payload is TEXT, and `?` would clash with JDBC placeholders. */
private class JsonbHasKey(private val column: Column<String>, private val key: String) : Op<Boolean>() {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) = queryBuilder {
        append("jsonb_exists(")
        append(column)
        append("::jsonb, ")
        registerArgument(TextColumnType(), key)
        append(")")
    }
}
