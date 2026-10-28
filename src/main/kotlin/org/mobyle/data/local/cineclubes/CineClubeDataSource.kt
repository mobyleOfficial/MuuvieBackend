package org.mobyle.data.local.cineclubes

import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inSubQuery
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.notInList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.mobyle.data.local.database.CineClubeParticipationsTable
import org.mobyle.data.local.database.CineClubeSessionsTable
import org.mobyle.data.local.database.CineClubesTable
import org.mobyle.data.local.database.MoviesTable
import org.mobyle.data.local.database.UserFollowsTable
import org.mobyle.data.local.database.UserMoviesTable
import org.mobyle.data.local.database.UsersTable
import org.mobyle.data.local.database.WatchLogsTable
import org.mobyle.data.local.database.userDbIdOf
import org.mobyle.data.local.feed.toUserSummary
import org.mobyle.data.local.shelves.SystemShelves
import org.mobyle.domain.model.CineClube
import org.mobyle.domain.model.CineClubeDraft
import org.mobyle.domain.model.CineClubeFilter
import org.mobyle.domain.model.CineClubeLog
import org.mobyle.domain.model.CineClubeMovie
import org.mobyle.domain.model.CineClubeParticipant
import org.mobyle.domain.model.CineClubeParticipation
import org.mobyle.domain.model.CineClubeSession
import org.mobyle.domain.model.CineClubeSummary
import org.mobyle.domain.model.DueSession
import org.mobyle.domain.model.Page
import org.mobyle.domain.model.PageRequest
import org.mobyle.domain.model.PasswordChange
import org.mobyle.domain.model.vocabulary.CineClubeStatus
import org.mobyle.domain.model.vocabulary.Emotion
import org.mobyle.domain.model.vocabulary.SessionProgress
import org.mobyle.domain.model.vocabulary.Visibility
import org.mobyle.domain.model.vocabulary.WatchMode
import org.mobyle.domain.repository.CineClubeRepository

/** Exposed implementation of the CineClubes; same contract as [CineClubeRepository]. */
interface CineClubeDataSource : CineClubeRepository

class CineClubeDataSourceImpl : CineClubeDataSource {

    override fun slugTaken(slug: String): Boolean = transaction {
        !CineClubesTable.selectAll().where { CineClubesTable.slug eq slug }.empty()
    }

    override fun create(ownerId: String, draft: CineClubeDraft, status: CineClubeStatus, passwordHash: String?): Long = transaction {
        val owner = userDbIdOf(ownerId) ?: throw IllegalArgumentException("Unknown user $ownerId")
        val id = CineClubesTable.insertAndGetId {
            it[slug] = draft.slug
            it[CineClubesTable.ownerId] = owner
            it[title] = draft.title
            it[description] = draft.description
            it[accentColor] = draft.accentColor
            it[CineClubesTable.passwordHash] = passwordHash
            it[debateLink] = draft.debateLink
            it[socialLink] = draft.socialLink
            it[CineClubesTable.status] = status.wireValue
            it[isCycle] = draft.isCycle
            it[startsOn] = draft.startsOn
            it[endsOn] = draft.endsOn
        }.value
        syncSessions(id, draft, emptySet())
        id
    }

    override fun update(id: Long, draft: CineClubeDraft, keepSessionIds: Set<Long>, password: PasswordChange) {
        transaction {
            CineClubesTable.update({ CineClubesTable.id eq id }) {
                it[title] = draft.title
                it[description] = draft.description
                it[accentColor] = draft.accentColor
                it[debateLink] = draft.debateLink
                it[socialLink] = draft.socialLink
                it[isCycle] = draft.isCycle
                it[startsOn] = draft.startsOn
                it[endsOn] = draft.endsOn
                it[updatedAt] = Clock.System.now()
                when (password) {
                    PasswordChange.Keep -> Unit
                    PasswordChange.Remove -> it[passwordHash] = null
                    is PasswordChange.Set -> it[passwordHash] = password.hash
                }
            }
            syncSessions(id, draft, keepSessionIds)
        }
    }

    override fun get(slug: String, today: LocalDate): CineClube? = transaction {
        (CineClubesTable innerJoin UsersTable).selectAll()
            .where { CineClubesTable.slug eq slug }
            .firstOrNull()
            ?.let { toCineClube(it, today) }
    }

    override fun getById(id: Long, today: LocalDate): CineClube? = transaction {
        (CineClubesTable innerJoin UsersTable).selectAll()
            .where { CineClubesTable.id eq id }
            .firstOrNull()
            ?.let { toCineClube(it, today) }
    }

    override fun passwordHash(id: Long): String? = transaction {
        CineClubesTable.select(CineClubesTable.passwordHash)
            .where { CineClubesTable.id eq id }
            .firstOrNull()
            ?.get(CineClubesTable.passwordHash)
    }

    override fun list(viewerId: String?, filter: CineClubeFilter, page: PageRequest, today: LocalDate): Page<CineClubeSummary> = transaction {
        val viewer = viewerId?.let { userDbIdOf(it) }
        val participant = filter.participant?.let { userDbIdOf(it) ?: return@transaction Page.of(emptyList(), page, 0) }
        var condition: Op<Boolean> = if (filter.mine) {
            if (viewer == null) return@transaction Page.of(emptyList(), page, 0)
            CineClubesTable.ownerId eq viewer
        } else if (participant != null) {
            CineClubesTable.status neq CineClubeStatus.CANCELLED.wireValue
        } else {
            val statuses = listOfNotNull(
                CineClubeStatus.UPCOMING, CineClubeStatus.ACTIVE, CineClubeStatus.FINISHED.takeIf { filter.includeFinished }
            )
            CineClubesTable.status inList statuses.map { it.wireValue }
        }
        filter.query?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { query ->
            val escaped = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
            condition = condition and (CineClubesTable.title.lowerCase() like "%$escaped%")
        }
        if (participant != null) {
            condition = condition and (CineClubesTable.id inSubQuery CineClubeParticipationsTable
                .select(CineClubeParticipationsTable.cineclubeId)
                .where { CineClubeParticipationsTable.userId eq participant })
        }
        filter.movieId?.let { movieId ->
            condition = condition and (CineClubesTable.id inSubQuery CineClubeSessionsTable
                .select(CineClubeSessionsTable.cineclubeId)
                .where { CineClubeSessionsTable.movieId eq movieId })
        }
        if (filter.open) condition = condition and (CineClubesTable.passwordHash.isNull())

        val total = CineClubesTable.selectAll().where { condition }.count().toInt()
        val rows = (CineClubesTable innerJoin UsersTable).selectAll()
            .where { condition }
            .orderBy(CineClubesTable.startsOn to SortOrder.DESC, CineClubesTable.id to SortOrder.DESC)
            .limit(page.limit, page.offset)
            .toList()
        val ids = rows.map { it[CineClubesTable.id].value }
        val sessions = sessions(ids)
        val participants = participantCounts(ids)
        val joined = viewer?.let {
            CineClubeParticipationsTable.select(CineClubeParticipationsTable.cineclubeId)
                .where { (CineClubeParticipationsTable.userId eq it) and (CineClubeParticipationsTable.cineclubeId inList ids) }
                .map { row -> row[CineClubeParticipationsTable.cineclubeId].value }
                .toSet()
        }.orEmpty()
        val results = rows.map { row ->
            val id = row[CineClubesTable.id].value
            val own = sessions[id].orEmpty()
            val current = CineClubeSession.current(own, today)
            CineClubeSummary(
                id = id,
                slug = row[CineClubesTable.slug],
                title = row[CineClubesTable.title],
                accentColor = row[CineClubesTable.accentColor],
                status = status(row),
                hasPassword = row[CineClubesTable.passwordHash] != null,
                isCycle = row[CineClubesTable.isCycle],
                owner = row.toUserSummary(),
                movie = current?.movie,
                startsOn = row[CineClubesTable.startsOn],
                endsOn = row[CineClubesTable.endsOn],
                debateOn = current?.debateOn,
                totalSessions = own.size,
                totalParticipants = participants[id] ?: 0,
                joined = id in joined
            )
        }
        Page.of(results, page, total)
    }

    override fun setStatus(id: Long, status: CineClubeStatus) {
        transaction {
            CineClubesTable.update({ CineClubesTable.id eq id }) {
                it[CineClubesTable.status] = status.wireValue
                it[updatedAt] = Clock.System.now()
            }
        }
    }

    override fun participation(cineclubeId: Long, userId: String): CineClubeParticipation? = transaction {
        val user = userDbIdOf(userId) ?: return@transaction null
        CineClubeParticipationsTable.selectAll()
            .where { (CineClubeParticipationsTable.cineclubeId eq cineclubeId) and (CineClubeParticipationsTable.userId eq user) }
            .firstOrNull()
            ?.toParticipation(userId)
    }

    override fun join(cineclubeId: Long, userId: String): CineClubeParticipation = transaction {
        val user = userDbIdOf(userId) ?: throw IllegalArgumentException("Unknown user $userId")
        val id = CineClubeParticipationsTable.insertAndGetId {
            it[CineClubeParticipationsTable.cineclubeId] = cineclubeId
            it[CineClubeParticipationsTable.userId] = user
        }
        CineClubeParticipationsTable.selectAll().where { CineClubeParticipationsTable.id eq id }.single().toParticipation(userId)
    }

    override fun leave(participationId: Long) {
        transaction { CineClubeParticipationsTable.deleteWhere { id eq participationId } }
    }

    override fun participantIds(cineclubeId: Long): List<String> = transaction {
        (CineClubeParticipationsTable innerJoin UsersTable).select(UsersTable.externalId)
            .where { CineClubeParticipationsTable.cineclubeId eq cineclubeId }
            .orderBy(CineClubeParticipationsTable.joinedAt to SortOrder.ASC)
            .map { it[UsersTable.externalId] }
    }

    override fun participants(
        cineclubeId: Long,
        session: CineClubeSession,
        sessions: List<CineClubeSession>
    ): List<CineClubeParticipant> = transaction {
        val rows = (CineClubeParticipationsTable innerJoin UsersTable).selectAll()
            .where { CineClubeParticipationsTable.cineclubeId eq cineclubeId }
            .orderBy(CineClubeParticipationsTable.joinedAt to SortOrder.ASC, CineClubeParticipationsTable.id to SortOrder.ASC)
            .toList()
        val userIds = rows.map { it[UsersTable.id].value }
        val all = (sessions + session).distinctBy { it.id }
        val logs: Map<Long, List<SessionLogRow>> = if (userIds.isEmpty()) emptyMap() else WatchLogsTable
            .select(WatchLogsTable.userId, WatchLogsTable.movieId, WatchLogsTable.watchedOn, WatchLogsTable.completed)
            .where {
                (WatchLogsTable.userId inList userIds) and (WatchLogsTable.movieId inList all.map { it.movie.id }.distinct()) and
                    (WatchLogsTable.watchedOn greaterEq all.minOf { it.startsOn }) and (WatchLogsTable.watchedOn lessEq all.maxOf { it.lastDay })
            }
            .map { SessionLogRow(it[WatchLogsTable.userId].value, it[WatchLogsTable.movieId].value, it[WatchLogsTable.watchedOn], it[WatchLogsTable.completed]) }
            .groupBy { it.userId }
        rows.map { row ->
            val mine = logs[row[UsersTable.id].value].orEmpty()
            val inSession = mine.filter { it.movieId == session.movie.id && session.counts(it.watchedOn) }
            CineClubeParticipant(
                user = row.toUserSummary(),
                progress = when {
                    inSession.any { it.completed } -> SessionProgress.COMPLETED
                    inSession.isNotEmpty() -> SessionProgress.WATCHING
                    else -> SessionProgress.PENDING
                },
                completedSessions = sessions.count { s -> mine.any { it.completed && it.movieId == s.movie.id && s.counts(it.watchedOn) } },
                lastWatchedOn = inSession.maxOfOrNull { it.watchedOn },
                joinedAt = row[CineClubeParticipationsTable.joinedAt]
            )
        }
    }

    override fun sessionLogs(cineclubeId: Long, session: CineClubeSession, viewerId: String?, page: PageRequest): Page<CineClubeLog> =
        transaction {
            val viewer = viewerId?.let { userDbIdOf(it) }
            val mutuals = viewer?.let { mutualsOf(it) }.orEmpty()
            var visible: Op<Boolean> = WatchLogsTable.visibility eq Visibility.PUBLIC.wireValue
            if (viewer != null) {
                visible = visible or (WatchLogsTable.userId eq viewer)
                if (mutuals.isNotEmpty()) {
                    visible = visible or ((WatchLogsTable.visibility eq Visibility.MUTUAL.wireValue) and (WatchLogsTable.userId inList mutuals))
                }
            }
            val condition = (WatchLogsTable.movieId eq session.movie.id) and
                (WatchLogsTable.watchedOn greaterEq session.startsOn) and (WatchLogsTable.watchedOn lessEq session.lastDay) and
                (WatchLogsTable.userId inSubQuery CineClubeParticipationsTable.select(CineClubeParticipationsTable.userId)
                    .where { CineClubeParticipationsTable.cineclubeId eq cineclubeId }) and
                visible
            val total = WatchLogsTable.selectAll().where { condition }.count().toInt()
            val results = (WatchLogsTable innerJoin UsersTable).selectAll()
                .where { condition }
                .orderBy(WatchLogsTable.createdAt to SortOrder.DESC, WatchLogsTable.id to SortOrder.DESC)
                .limit(page.limit, page.offset)
                .map { row ->
                    CineClubeLog(
                        id = row[WatchLogsTable.id].value,
                        user = row.toUserSummary(),
                        sessionId = session.id,
                        watchedOn = row[WatchLogsTable.watchedOn],
                        mode = WatchMode.fromWireValue(row[WatchLogsTable.mode]) ?: WatchMode.WATCHED,
                        minutesWatched = row[WatchLogsTable.minutesWatched],
                        percent = row[WatchLogsTable.percent]?.toInt(),
                        completed = row[WatchLogsTable.completed],
                        emotions = row[WatchLogsTable.emotionIds].mapNotNull { Emotion.fromId(it.toInt()) },
                        gifUrl = row[WatchLogsTable.gifUrl],
                        comment = row[WatchLogsTable.comment],
                        hasSpoiler = row[WatchLogsTable.hasSpoiler],
                        hidden = false,
                        hiddenReason = null,
                        createdAt = row[WatchLogsTable.createdAt]
                    )
                }
            Page.of(results, page, total)
        }

    override fun hasWatched(userId: String, movieId: Long): Boolean = transaction {
        val user = userDbIdOf(userId) ?: return@transaction false
        val watched = !UserMoviesTable.selectAll()
            .where {
                (UserMoviesTable.userId eq user) and (UserMoviesTable.movieId eq movieId) and
                    (UserMoviesTable.status eq SystemShelves.STATUS_WATCHED)
            }
            .empty()
        watched || !WatchLogsTable.selectAll()
            .where { (WatchLogsTable.userId eq user) and (WatchLogsTable.movieId eq movieId) and (WatchLogsTable.completed eq true) }
            .empty()
    }

    override fun sessionsToOpen(today: LocalDate): List<DueSession> = transaction {
        dueSessions {
            (CineClubeSessionsTable.openedAt.isNull()) and (CineClubeSessionsTable.startsOn lessEq today) and
                (CineClubesTable.status inList listOf(CineClubeStatus.UPCOMING.wireValue, CineClubeStatus.ACTIVE.wireValue))
        }
    }

    override fun markOpened(sessionId: Long) {
        transaction {
            CineClubeSessionsTable.update({ CineClubeSessionsTable.id eq sessionId }) { it[openedAt] = Clock.System.now() }
        }
    }

    override fun debatesToNotify(today: LocalDate): List<DueSession> = transaction {
        dueSessions {
            (CineClubeSessionsTable.debateNotifiedAt.isNull()) and (CineClubeSessionsTable.debateOn lessEq today) and
                (CineClubesTable.status inList listOf(CineClubeStatus.UPCOMING.wireValue, CineClubeStatus.ACTIVE.wireValue))
        }
    }

    override fun markDebateNotified(sessionId: Long) {
        transaction {
            CineClubeSessionsTable.update({ CineClubeSessionsTable.id eq sessionId }) { it[debateNotifiedAt] = Clock.System.now() }
        }
    }

    override fun dueToStart(today: LocalDate): List<Long> = transaction {
        CineClubesTable.select(CineClubesTable.id)
            .where {
                (CineClubesTable.status eq CineClubeStatus.UPCOMING.wireValue) and (CineClubesTable.startsOn lessEq today) and
                    (CineClubesTable.endsOn greaterEq today)
            }
            .map { it[CineClubesTable.id].value }
    }

    override fun dueToFinish(today: LocalDate): List<Long> = transaction {
        CineClubesTable.select(CineClubesTable.id)
            .where {
                (CineClubesTable.status inList listOf(CineClubeStatus.UPCOMING.wireValue, CineClubeStatus.ACTIVE.wireValue)) and
                    (CineClubesTable.endsOn less today)
            }
            .map { it[CineClubesTable.id].value }
    }

    override fun participationCount(userId: String): Int = transaction {
        val user = userDbIdOf(userId) ?: return@transaction 0
        (CineClubeParticipationsTable innerJoin CineClubesTable).selectAll()
            .where {
                (CineClubeParticipationsTable.userId eq user) and (CineClubesTable.status neq CineClubeStatus.CANCELLED.wireValue)
            }
            .count()
            .toInt()
    }

    // ── Helpers (inside a transaction) ──────────────────────────────────────

    private fun toCineClube(row: ResultRow, today: LocalDate): CineClube {
        val id = row[CineClubesTable.id].value
        val sessions = sessions(listOf(id))[id].orEmpty()
        return CineClube(
            id = id,
            slug = row[CineClubesTable.slug],
            title = row[CineClubesTable.title],
            description = row[CineClubesTable.description],
            accentColor = row[CineClubesTable.accentColor],
            status = status(row),
            hasPassword = row[CineClubesTable.passwordHash] != null,
            debateLink = row[CineClubesTable.debateLink],
            socialLink = row[CineClubesTable.socialLink],
            isCycle = row[CineClubesTable.isCycle],
            startsOn = row[CineClubesTable.startsOn],
            endsOn = row[CineClubesTable.endsOn],
            owner = row.toUserSummary(),
            sessions = sessions,
            currentSessionId = CineClubeSession.current(sessions, today)?.id,
            totalParticipants = participantCounts(listOf(id))[id] ?: 0,
            createdAt = row[CineClubesTable.createdAt]
        )
    }

    /** Sessions of the CineClubes, in order, with their movies. */
    private fun sessions(cineclubeIds: List<Long>): Map<Long, List<CineClubeSession>> {
        if (cineclubeIds.isEmpty()) return emptyMap()
        return (CineClubeSessionsTable innerJoin MoviesTable).selectAll()
            .where { CineClubeSessionsTable.cineclubeId inList cineclubeIds }
            .orderBy(CineClubeSessionsTable.position to SortOrder.ASC, CineClubeSessionsTable.startsOn to SortOrder.ASC)
            .groupBy({ it[CineClubeSessionsTable.cineclubeId].value }, ::toSession)
    }

    private fun toSession(row: ResultRow) = CineClubeSession(
        id = row[CineClubeSessionsTable.id].value,
        position = row[CineClubeSessionsTable.position],
        movie = CineClubeMovie(
            id = row[MoviesTable.id].value,
            tmdbId = row[MoviesTable.tmdbId],
            title = row[MoviesTable.localTitle] ?: row[MoviesTable.title],
            posterPath = row[MoviesTable.posterPath],
            year = row[MoviesTable.year] ?: row[MoviesTable.releaseDate]?.take(4)?.toIntOrNull(),
            runtime = row[MoviesTable.runtime]
        ),
        startsOn = row[CineClubeSessionsTable.startsOn],
        endsOn = row[CineClubeSessionsTable.endsOn],
        debateOn = row[CineClubeSessionsTable.debateOn]
    )

    private fun dueSessions(condition: () -> Op<Boolean>): List<DueSession> =
        (CineClubeSessionsTable innerJoin MoviesTable innerJoin CineClubesTable).selectAll()
            .where { condition() }
            .orderBy(CineClubeSessionsTable.startsOn to SortOrder.ASC)
            .map { DueSession(it[CineClubeSessionsTable.cineclubeId].value, toSession(it)) }

    /** Updates the sessions with an id, inserts the new ones and deletes the rest but [keep]. */
    private fun syncSessions(cineclubeId: Long, draft: CineClubeDraft, keep: Set<Long>) {
        val stored = CineClubeSessionsTable.select(CineClubeSessionsTable.id)
            .where { CineClubeSessionsTable.cineclubeId eq cineclubeId }
            .map { it[CineClubeSessionsTable.id].value }
            .toSet()
        val kept = draft.sessions.sortedBy { it.startsOn }.mapIndexed { position, session ->
            if (session.id != null && session.id in stored) {
                if (session.id !in keep) {
                    CineClubeSessionsTable.update({ CineClubeSessionsTable.id eq session.id }) {
                        it[movieId] = session.movieId
                        it[startsOn] = session.startsOn
                        it[endsOn] = session.endsOn
                        it[debateOn] = session.debateOn
                        it[CineClubeSessionsTable.position] = position
                    }
                } else {
                    CineClubeSessionsTable.update({ CineClubeSessionsTable.id eq session.id }) {
                        it[CineClubeSessionsTable.position] = position
                    }
                }
                session.id
            } else {
                CineClubeSessionsTable.insertAndGetId {
                    it[CineClubeSessionsTable.cineclubeId] = cineclubeId
                    it[movieId] = session.movieId
                    it[startsOn] = session.startsOn
                    it[endsOn] = session.endsOn
                    it[debateOn] = session.debateOn
                    it[CineClubeSessionsTable.position] = position
                }.value
            }
        }
        CineClubeSessionsTable.deleteWhere {
            (CineClubeSessionsTable.cineclubeId eq cineclubeId) and (CineClubeSessionsTable.id notInList (kept + keep))
        }
    }

    private fun participantCounts(cineclubeIds: List<Long>): Map<Long, Int> =
        CineClubeParticipationsTable.select(CineClubeParticipationsTable.cineclubeId)
            .where { CineClubeParticipationsTable.cineclubeId inList cineclubeIds }
            .map { it[CineClubeParticipationsTable.cineclubeId].value }
            .groupingBy { it }
            .eachCount()

    /** Users who follow [userDbId] and are followed back. */
    private fun mutualsOf(userDbId: Long): List<Long> {
        val following = UserFollowsTable.select(UserFollowsTable.followedId)
            .where { UserFollowsTable.followerId eq userDbId }
            .map { it[UserFollowsTable.followedId].value }
        if (following.isEmpty()) return emptyList()
        return UserFollowsTable.select(UserFollowsTable.followerId)
            .where { (UserFollowsTable.followedId eq userDbId) and (UserFollowsTable.followerId inList following) }
            .map { it[UserFollowsTable.followerId].value }
    }

    private fun ResultRow.toParticipation(userId: String) = CineClubeParticipation(
        id = this[CineClubeParticipationsTable.id].value,
        cineclubeId = this[CineClubeParticipationsTable.cineclubeId].value,
        userId = userId,
        joinedAt = this[CineClubeParticipationsTable.joinedAt]
    )

    private fun status(row: ResultRow) = CineClubeStatus.fromWireValue(row[CineClubesTable.status]) ?: CineClubeStatus.UPCOMING

    private data class SessionLogRow(val userId: Long, val movieId: Long, val watchedOn: LocalDate, val completed: Boolean)
}
