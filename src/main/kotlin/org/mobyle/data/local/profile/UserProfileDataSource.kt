package org.mobyle.data.local.profile

import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.notInSubQuery
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.mobyle.data.local.database.LocadoraShelvesTable
import org.mobyle.data.local.database.LocadorasTable
import org.mobyle.data.local.database.MaratonaParticipationsTable
import org.mobyle.data.local.database.CineClubeParticipationsTable
import org.mobyle.data.local.database.CineClubesTable
import org.mobyle.data.local.database.MaratonasTable
import org.mobyle.data.local.database.MoviesTable
import org.mobyle.data.local.database.ProfileHighlightsTable
import org.mobyle.data.local.database.ShelvesTable
import org.mobyle.data.local.database.UserFollowsTable
import org.mobyle.data.local.database.UserMoviesTable
import org.mobyle.data.local.database.UsersTable
import org.mobyle.data.local.database.WatchLogsTable
import org.mobyle.data.local.shelves.SystemShelves
import org.mobyle.domain.model.HighlightDraft
import org.mobyle.domain.model.MovieCard
import org.mobyle.domain.model.ProfileDraft
import org.mobyle.domain.model.ProfileHighlight
import org.mobyle.domain.model.ProfileRecord
import org.mobyle.domain.model.ProfileStats
import org.mobyle.domain.model.vocabulary.HighlightType
import org.mobyle.domain.model.vocabulary.CineClubeStatus
import org.mobyle.domain.model.vocabulary.MaratonaStatus
import org.mobyle.domain.model.vocabulary.SystemShelfType
import org.mobyle.domain.model.vocabulary.Visibility

/**
 * The public profile of the v1 API (B5): profile fields, @handle, counters and highlights.
 * Users are identified by their external id.
 */
interface UserProfileDataSource {
    fun getProfile(userId: String): ProfileRecord?
    fun findUserIdByHandle(handle: String): String?
    fun isHandleTaken(handle: String, exceptUserId: String?): Boolean
    fun updateProfile(userId: String, draft: ProfileDraft)
    /** Counters as a viewer allowed to see [visibilities] sees them. */
    fun stats(ownerId: String, visibilities: Set<Visibility>): ProfileStats
    fun isFollowing(followerId: String, followedId: String): Boolean
    /** The stored highlights, in order, without what they point to. */
    fun getHighlights(ownerId: String): List<ProfileHighlight>
    /** Replaces every highlight of the user; the list order is the position. */
    fun saveHighlights(ownerId: String, highlights: List<HighlightDraft>)
    /** Movies in the order of [movieIds]; unknown ids are left out. */
    fun movieCards(movieIds: List<Long>): List<MovieCard>
}

class UserProfileDataSourceImpl : UserProfileDataSource {

    private val json = Json { ignoreUnknownKeys = true }

    override fun getProfile(userId: String): ProfileRecord? = transaction {
        UsersTable.selectAll().where { UsersTable.externalId eq userId }.firstOrNull()?.let(::toRecord)
    }

    override fun findUserIdByHandle(handle: String): String? = transaction {
        UsersTable.select(UsersTable.externalId)
            .where { UsersTable.handle eq handle }
            .firstOrNull()
            ?.get(UsersTable.externalId)
    }

    override fun isHandleTaken(handle: String, exceptUserId: String?): Boolean = transaction {
        UsersTable.selectAll()
            .where {
                val sameHandle = UsersTable.handle eq handle
                if (exceptUserId == null) sameHandle else sameHandle and (UsersTable.externalId neq exceptUserId)
            }
            .count() > 0
    }

    override fun updateProfile(userId: String, draft: ProfileDraft) {
        transaction {
            UsersTable.update({ UsersTable.externalId eq userId }) {
                it[displayName] = draft.displayName
                if (draft.handle != null) it[handle] = draft.handle
                it[pronouns] = draft.pronouns
                it[bio] = draft.bio
                it[links] = draft.links
                it[accentColor] = draft.accentColor
                it[avatarUrl] = draft.avatarUrl
                it[bannerUrl] = draft.bannerUrl
            }
        }
    }

    override fun stats(ownerId: String, visibilities: Set<Visibility>): ProfileStats = transaction {
        val ownerDbId = userDbId(ownerId) ?: return@transaction ProfileStats(0, 0, 0, 0, followers = 0, following = 0)
        val wireValues = visibilities.map { it.wireValue }
        val visibleShelf = (ShelvesTable.userId eq ownerDbId) and (ShelvesTable.visibility inList wireValues)
        // The shelves of a locadora that hides them from the profile are not counted, not even for the owner (F4.1).
        val shelfCondition: Op<Boolean> = visibleShelf and
            (ShelvesTable.id notInSubQuery (LocadoraShelvesTable innerJoin LocadorasTable)
                .select(LocadoraShelvesTable.shelfId)
                .where { LocadorasTable.hideShelvesInProfile eq true })
        val shelves = ShelvesTable.selectAll().where { shelfCondition }.count().toInt()
        val locadoras = LocadorasTable.selectAll()
            .where { (LocadorasTable.userId eq ownerDbId) and (LocadorasTable.visibility inList wireValues) }
            .count().toInt()
        val watchLogs = WatchLogsTable.selectAll()
            .where { (WatchLogsTable.userId eq ownerDbId) and (WatchLogsTable.visibility inList wireValues) }
            .count().toInt()
        // The watched count follows the visibility of "Já assisti".
        val watchedShelfVisible = ShelvesTable.selectAll()
            .where {
                (ShelvesTable.userId eq ownerDbId) and (ShelvesTable.systemType eq SystemShelfType.WATCHED.wireValue) and
                    (ShelvesTable.visibility inList wireValues)
            }
            .count() > 0
        val watched = if (!watchedShelfVisible) 0 else UserMoviesTable.selectAll()
            .where { (UserMoviesTable.userId eq ownerDbId) and (UserMoviesTable.status eq SystemShelves.STATUS_WATCHED) }
            .count().toInt()
        ProfileStats(
            shelves = shelves,
            locadoras = locadoras,
            watchLogs = watchLogs,
            watched = watched,
            // Maratonas the user created (not cancelled) and the ones they take part in (B7).
            maratonas = MaratonasTable.selectAll()
                .where { (MaratonasTable.ownerId eq ownerDbId) and (MaratonasTable.status neq MaratonaStatus.CANCELLED.wireValue) }
                .count().toInt(),
            // CineClubes the user takes part in (B8), cancelled ones left out.
            cineclubes = (CineClubeParticipationsTable innerJoin CineClubesTable).selectAll()
                .where { (CineClubeParticipationsTable.userId eq ownerDbId) and (CineClubesTable.status neq CineClubeStatus.CANCELLED.wireValue) }
                .count().toInt(),
            participations = MaratonaParticipationsTable.selectAll()
                .where { MaratonaParticipationsTable.userId eq ownerDbId }
                .count().toInt(),
            followers = UserFollowsTable.selectAll().where { UserFollowsTable.followedId eq ownerDbId }.count().toInt(),
            following = UserFollowsTable.selectAll().where { UserFollowsTable.followerId eq ownerDbId }.count().toInt()
        )
    }

    override fun isFollowing(followerId: String, followedId: String): Boolean = transaction {
        val follower = userDbId(followerId) ?: return@transaction false
        val followed = userDbId(followedId) ?: return@transaction false
        UserFollowsTable.selectAll()
            .where { (UserFollowsTable.followerId eq follower) and (UserFollowsTable.followedId eq followed) }
            .count() > 0
    }

    override fun getHighlights(ownerId: String): List<ProfileHighlight> = transaction {
        val ownerDbId = userDbId(ownerId) ?: return@transaction emptyList()
        ProfileHighlightsTable.selectAll()
            .where { ProfileHighlightsTable.userId eq ownerDbId }
            .orderBy(ProfileHighlightsTable.position to SortOrder.ASC)
            .mapNotNull(::toHighlight)
    }

    override fun saveHighlights(ownerId: String, highlights: List<HighlightDraft>) {
        transaction {
            val ownerDbId = userDbId(ownerId) ?: throw IllegalStateException("User not found")
            val now = Clock.System.now()
            ProfileHighlightsTable.deleteWhere { userId eq ownerDbId }
            highlights.forEachIndexed { index, highlight ->
                ProfileHighlightsTable.insert {
                    it[userId] = ownerDbId
                    it[type] = highlight.type.wireValue
                    it[position] = index
                    it[enabled] = highlight.enabled
                    it[payload] = json.encodeToString(HighlightPayload.serializer(), HighlightPayload.of(highlight))
                    it[updatedAt] = now
                }
            }
        }
    }

    override fun movieCards(movieIds: List<Long>): List<MovieCard> = transaction {
        if (movieIds.isEmpty()) return@transaction emptyList()
        val byId = MoviesTable.selectAll()
            .where { MoviesTable.id inList movieIds }
            .associate { row ->
                row[MoviesTable.id].value to MovieCard(
                    movieId = row[MoviesTable.id].value,
                    tmdbId = row[MoviesTable.tmdbId],
                    title = row[MoviesTable.localTitle] ?: row[MoviesTable.title],
                    posterPath = row[MoviesTable.posterPath],
                    year = row[MoviesTable.year]
                )
            }
        movieIds.mapNotNull { byId[it] }
    }

    // ── Helpers (inside a transaction) ──────────────────────────────────────

    private fun userDbId(userId: String): Long? = UsersTable.select(UsersTable.id)
        .where { UsersTable.externalId eq userId }
        .firstOrNull()
        ?.get(UsersTable.id)?.value

    private fun toRecord(row: ResultRow) = ProfileRecord(
        id = row[UsersTable.externalId],
        username = row[UsersTable.username],
        handle = row[UsersTable.handle],
        displayName = row[UsersTable.displayName],
        pronouns = row[UsersTable.pronouns],
        bio = row[UsersTable.bio],
        avatarUrl = row[UsersTable.avatarUrl],
        bannerUrl = row[UsersTable.bannerUrl],
        accentColor = row[UsersTable.accentColor],
        links = row[UsersTable.links],
        featured = row[UsersTable.featured]
    )

    private fun toHighlight(row: ResultRow): ProfileHighlight? {
        val type = HighlightType.fromWireValue(row[ProfileHighlightsTable.type]) ?: return null
        val payload = json.decodeFromString(HighlightPayload.serializer(), row[ProfileHighlightsTable.payload])
        return ProfileHighlight(
            type = type,
            position = row[ProfileHighlightsTable.position],
            enabled = row[ProfileHighlightsTable.enabled],
            movieIds = payload.movieIds,
            shelfId = payload.shelfId,
            locadoraId = payload.locadoraId,
            soundtrackUrl = payload.url
        )
    }

    /** What a highlight stores in its JSON payload. */
    @Serializable
    private data class HighlightPayload(
        val movieIds: List<Long> = emptyList(),
        val shelfId: Long? = null,
        val locadoraId: Long? = null,
        val url: String? = null
    ) {
        companion object {
            fun of(highlight: HighlightDraft) = HighlightPayload(
                movieIds = highlight.movieIds,
                shelfId = highlight.shelfId,
                locadoraId = highlight.locadoraId,
                url = highlight.soundtrackUrl
            )
        }
    }
}
