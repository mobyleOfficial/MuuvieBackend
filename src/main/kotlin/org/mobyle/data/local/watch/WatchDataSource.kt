package org.mobyle.data.local.watch

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inSubQuery
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNotNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.statements.UpdateBuilder
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.mobyle.data.local.database.MoviesTable
import org.mobyle.data.local.database.ShelfItemsTable
import org.mobyle.data.local.database.ShelvesTable
import org.mobyle.data.local.database.UserMoviesTable
import org.mobyle.data.local.database.UsersTable
import org.mobyle.data.local.database.WatchLogsTable
import org.mobyle.data.local.database.userDbIdOf
import org.mobyle.data.local.shelves.SystemShelves
import org.mobyle.domain.model.DiaryEntry
import org.mobyle.domain.model.EmotionCount
import org.mobyle.domain.model.Gif
import org.mobyle.domain.model.MovieActivity
import org.mobyle.domain.model.MovieCard
import org.mobyle.domain.model.MovieCommunity
import org.mobyle.domain.model.MovieListState
import org.mobyle.domain.model.MovieRating
import org.mobyle.domain.model.Page
import org.mobyle.domain.model.PageRequest
import org.mobyle.domain.model.UserMovieRecord
import org.mobyle.domain.model.WatchLog
import org.mobyle.domain.model.WatchLogDraft
import org.mobyle.domain.model.WatchLogSummary
import org.mobyle.domain.model.WatchProgress
import org.mobyle.domain.model.vocabulary.Emotion
import org.mobyle.domain.model.vocabulary.Rating
import org.mobyle.domain.model.vocabulary.SystemShelfType
import org.mobyle.domain.model.vocabulary.Visibility
import org.mobyle.domain.model.vocabulary.WatchMode
import org.mobyle.domain.model.vocabulary.WatchStatus
import org.mobyle.domain.model.vocabulary.WatchVenue
import org.mobyle.domain.repository.WatchableMovie

/** Watch logs and the user_movies state they summarize (B2). Users are identified by their external id. */
interface WatchDataSource {
    fun getMovie(movieId: Long): WatchableMovie?
    fun getUserMovie(userId: String, movieId: Long): UserMovieRecord?
    fun createLog(userId: String, movieId: Long, draft: WatchLogDraft): WatchLog
    fun getLog(logId: Long): WatchLog?
    fun updateLog(logId: Long, draft: WatchLogDraft): WatchLog
    fun deleteLog(logId: Long)
    fun getLogs(ownerId: String, movieId: Long, visibilities: Set<Visibility>, page: PageRequest): Page<WatchLog>
    fun getUserLogs(ownerId: String, visibilities: Set<Visibility>, page: PageRequest): Page<DiaryEntry>
    fun summary(ownerId: String, movieId: Long, visibilities: Set<Visibility>): WatchLogSummary
    fun startRewatch(userId: String, movieId: Long): WatchProgress
    fun setRating(userId: String, movieId: Long, rating: Rating, review: String?, hasSpoiler: Boolean): MovieRating
    fun removeRating(userId: String, movieId: Long)
    fun watchingNow(userId: String): Long?
    fun setWatchingNow(userId: String, movieId: Long?)
    fun recentEmotions(userId: String, limit: Int): List<Emotion>
    fun recentGifs(userId: String, limit: Int): List<Gif>
    fun community(movieId: Long): MovieCommunity
    fun activity(movieId: Long, from: LocalDate, until: LocalDate): MovieActivity
    fun listStates(userId: String, movieIds: List<Long>): Map<Long, MovieListState>
}

class WatchDataSourceImpl : WatchDataSource {

    override fun getMovie(movieId: Long): WatchableMovie? = transaction {
        MoviesTable.selectAll().where { MoviesTable.id eq movieId }.firstOrNull()?.let {
            WatchableMovie(
                id = it[MoviesTable.id].value,
                tmdbId = it[MoviesTable.tmdbId],
                title = it[MoviesTable.localTitle] ?: it[MoviesTable.title],
                posterPath = it[MoviesTable.posterPath],
                runtime = it[MoviesTable.runtime]
            )
        }
    }

    override fun getUserMovie(userId: String, movieId: Long): UserMovieRecord? = transaction {
        val userDbId = userDbIdOf(userId) ?: return@transaction null
        userMovieRow(userDbId, movieId)?.let { toRecord(it) }
    }

    override fun createLog(userId: String, movieId: Long, draft: WatchLogDraft): WatchLog = transaction {
        val userDbId = userDbIdOf(userId) ?: throw IllegalStateException("User not found")
        val now = Clock.System.now()
        val userMovie = ensureUserMovie(userDbId, movieId, now)
        val logId = WatchLogsTable.insertAndGetId {
            it[WatchLogsTable.userId] = userDbId
            it[WatchLogsTable.movieId] = movieId
            it[cycle] = userMovie[UserMoviesTable.currentCycle]
            fill(it, draft)
            it[createdAt] = now
            it[updatedAt] = now
        }.value
        recomputeProgress(userDbId, movieId)
        applyCompletion(userDbId, movieId, userMovie[UserMoviesTable.status], draft.completed, now)
        getLog(logId)!!
    }

    override fun getLog(logId: Long): WatchLog? = transaction {
        (WatchLogsTable innerJoin UsersTable).selectAll()
            .where { WatchLogsTable.id eq logId }
            .firstOrNull()
            ?.let { toWatchLog(it) }
    }

    override fun updateLog(logId: Long, draft: WatchLogDraft): WatchLog = transaction {
        val log = WatchLogsTable.selectAll().where { WatchLogsTable.id eq logId }.first()
        val userDbId = log[WatchLogsTable.userId].value
        val movieId = log[WatchLogsTable.movieId].value
        val now = Clock.System.now()
        WatchLogsTable.update({ WatchLogsTable.id eq logId }) {
            fill(it, draft)
            it[updatedAt] = now
        }
        recomputeProgress(userDbId, movieId)
        val status = userMovieRow(userDbId, movieId)?.get(UserMoviesTable.status) ?: SystemShelves.STATUS_NONE
        applyCompletion(userDbId, movieId, status, draft.completed, now)
        getLog(logId)!!
    }

    override fun deleteLog(logId: Long) {
        transaction {
            val log = WatchLogsTable.selectAll().where { WatchLogsTable.id eq logId }.firstOrNull() ?: return@transaction
            WatchLogsTable.deleteWhere { WatchLogsTable.id eq logId }
            recomputeProgress(log[WatchLogsTable.userId].value, log[WatchLogsTable.movieId].value)
        }
    }

    override fun getLogs(ownerId: String, movieId: Long, visibilities: Set<Visibility>, page: PageRequest): Page<WatchLog> =
        transaction {
            val ownerDbId = userDbIdOf(ownerId) ?: return@transaction Page.of(emptyList(), page, 0)
            val query = (WatchLogsTable innerJoin UsersTable).selectAll()
                .where {
                    (WatchLogsTable.userId eq ownerDbId) and (WatchLogsTable.movieId eq movieId) and
                        (WatchLogsTable.visibility inList visibilities.map { it.wireValue })
                }
            val total = query.count().toInt()
            val logs = query
                .orderBy(
                    WatchLogsTable.watchedOn to SortOrder.DESC,
                    WatchLogsTable.createdAt to SortOrder.DESC,
                    WatchLogsTable.id to SortOrder.DESC
                )
                .limit(page.limit, offset = page.offset)
                .map { toWatchLog(it) }
            Page.of(logs, page, total)
        }

    override fun getUserLogs(ownerId: String, visibilities: Set<Visibility>, page: PageRequest): Page<DiaryEntry> =
        transaction {
            val ownerDbId = userDbIdOf(ownerId) ?: return@transaction Page.of(emptyList(), page, 0)
            val query = (WatchLogsTable innerJoin UsersTable innerJoin MoviesTable).selectAll()
                .where {
                    (WatchLogsTable.userId eq ownerDbId) and
                        (WatchLogsTable.visibility inList visibilities.map { it.wireValue })
                }
            val total = query.count().toInt()
            val entries = query
                .orderBy(
                    WatchLogsTable.watchedOn to SortOrder.DESC,
                    WatchLogsTable.createdAt to SortOrder.DESC,
                    WatchLogsTable.id to SortOrder.DESC
                )
                .limit(page.limit, offset = page.offset)
                .map { row ->
                    DiaryEntry(
                        log = toWatchLog(row),
                        movie = MovieCard(
                            movieId = row[MoviesTable.id].value,
                            tmdbId = row[MoviesTable.tmdbId],
                            title = row[MoviesTable.localTitle] ?: row[MoviesTable.title],
                            posterPath = row[MoviesTable.posterPath],
                            year = row[MoviesTable.year]
                        )
                    )
                }
            Page.of(entries, page, total)
        }

    override fun summary(ownerId: String, movieId: Long, visibilities: Set<Visibility>): WatchLogSummary = transaction {
        val ownerDbId = userDbIdOf(ownerId)
        val userMovie = ownerDbId?.let { userMovieRow(it, movieId) }
        val logs = if (ownerDbId == null) emptyList() else WatchLogsTable.selectAll()
            .where {
                (WatchLogsTable.userId eq ownerDbId) and (WatchLogsTable.movieId eq movieId) and
                    (WatchLogsTable.visibility inList visibilities.map { it.wireValue })
            }
            .toList()
        val runtime = MoviesTable.select(MoviesTable.runtime).where { MoviesTable.id eq movieId }
            .firstOrNull()?.get(MoviesTable.runtime)
        // A cycle counts its furthest point, or the whole movie once completed.
        val totalMinutes = logs.groupBy { it[WatchLogsTable.cycle] }.values.sumOf { cycle ->
            val furthest = cycle.mapNotNull { it[WatchLogsTable.minutesWatched] }.maxOrNull() ?: 0
            if (cycle.any { it[WatchLogsTable.completed] }) runtime ?: furthest else furthest
        }
        val topEmotions = logs.flatMap { it[WatchLogsTable.emotionIds] }
            .groupingBy { it }.eachCount()
            .entries.sortedWith(compareByDescending<Map.Entry<Short, Int>> { it.value }.thenBy { it.key })
            .mapNotNull { Emotion.fromId(it.key.toInt()) }
            .take(TOP_EMOTIONS)
        WatchLogSummary(
            totalMinutes = totalMinutes,
            sessions = logs.size,
            rewatches = userMovie?.get(UserMoviesTable.rewatches) ?: 0,
            currentCycle = userMovie?.get(UserMoviesTable.currentCycle) ?: 1,
            topEmotions = topEmotions
        )
    }

    override fun startRewatch(userId: String, movieId: Long): WatchProgress = transaction {
        val userDbId = userDbIdOf(userId) ?: throw IllegalStateException("User not found")
        val row = ensureUserMovie(userDbId, movieId, Clock.System.now())
        val nextCycle = row[UserMoviesTable.currentCycle] + 1
        UserMoviesTable.update({ UserMoviesTable.id eq row[UserMoviesTable.id] }) {
            it[currentCycle] = nextCycle
            it[rewatches] = row[UserMoviesTable.rewatches] + 1
            it[progressMinutes] = null
            it[progressPercent] = null
            it[updatedAt] = Clock.System.now()
        }
        WatchProgress(cycle = nextCycle, minutes = null, percent = null, completed = false)
    }

    override fun setRating(userId: String, movieId: Long, rating: Rating, review: String?, hasSpoiler: Boolean): MovieRating =
        transaction {
            val userDbId = userDbIdOf(userId) ?: throw IllegalStateException("User not found")
            val now = Clock.System.now()
            val row = ensureUserMovie(userDbId, movieId, now)
            UserMoviesTable.update({ UserMoviesTable.id eq row[UserMoviesTable.id] }) {
                it[UserMoviesTable.rating] = rating.wireValue
                it[UserMoviesTable.review] = review
                it[reviewHasSpoiler] = hasSpoiler
                it[ratedAt] = now
                it[importSource] = "manual"
                it[updatedAt] = now
            }
            MovieRating(
                id = row[UserMoviesTable.id].value,
                rating = rating,
                review = review,
                hasSpoiler = hasSpoiler,
                ratedAt = now
            )
        }

    override fun removeRating(userId: String, movieId: Long) {
        transaction {
            val userDbId = userDbIdOf(userId) ?: return@transaction
            UserMoviesTable.update({ (UserMoviesTable.userId eq userDbId) and (UserMoviesTable.movieId eq movieId) }) {
                it[rating] = null
                it[review] = null
                it[reviewHasSpoiler] = false
                it[ratedAt] = null
                it[updatedAt] = Clock.System.now()
            }
        }
    }

    override fun watchingNow(userId: String): Long? = transaction {
        UsersTable.select(UsersTable.watchingNowMovieId)
            .where { UsersTable.externalId eq userId }
            .firstOrNull()
            ?.get(UsersTable.watchingNowMovieId)
    }

    override fun setWatchingNow(userId: String, movieId: Long?) {
        transaction {
            UsersTable.update({ UsersTable.externalId eq userId }) { it[watchingNowMovieId] = movieId }
        }
    }

    override fun recentEmotions(userId: String, limit: Int): List<Emotion> = transaction {
        val userDbId = userDbIdOf(userId) ?: return@transaction emptyList()
        recentLogs(userDbId)
            .flatMap { it[WatchLogsTable.emotionIds] }
            .distinct()
            .mapNotNull { Emotion.fromId(it.toInt()) }
            .take(limit)
    }

    override fun recentGifs(userId: String, limit: Int): List<Gif> = transaction {
        val userDbId = userDbIdOf(userId) ?: return@transaction emptyList()
        recentLogs(userDbId)
            .mapNotNull { row ->
                val id = row[WatchLogsTable.gifId] ?: return@mapNotNull null
                val url = row[WatchLogsTable.gifUrl] ?: return@mapNotNull null
                Gif(id, url)
            }
            .distinctBy { it.id }
            .take(limit)
    }

    override fun community(movieId: Long): MovieCommunity = transaction {
        fun publicShelf(type: SystemShelfType) =
            (ShelvesTable.systemType eq type.wireValue) and (ShelvesTable.visibility eq Visibility.PUBLIC.wireValue)
        fun onPublicShelf(type: SystemShelfType) = (ShelfItemsTable innerJoin ShelvesTable).selectAll()
            .where { (ShelfItemsTable.movieId eq movieId) and publicShelf(type) }
            .count().toInt()
        // A rating follows the visibility of Já assisti.
        val publicWatchers = ShelvesTable.select(ShelvesTable.userId).where { publicShelf(SystemShelfType.WATCHED) }
        val ratings = UserMoviesTable.select(UserMoviesTable.rating)
            .where {
                (UserMoviesTable.movieId eq movieId) and UserMoviesTable.rating.isNotNull() and
                    (UserMoviesTable.userId inSubQuery publicWatchers)
            }
            .mapNotNull { row -> row[UserMoviesTable.rating]?.let { Rating.fromWireValue(it) } }
        val shelves = (ShelfItemsTable innerJoin ShelvesTable).selectAll()
            .where {
                (ShelfItemsTable.movieId eq movieId) and ShelvesTable.systemType.isNull() and
                    (ShelvesTable.visibility eq Visibility.PUBLIC.wireValue)
            }
            .count().toInt()
        // The full distribution, most frequent first (B13.5).
        val emotions = WatchLogsTable.select(WatchLogsTable.emotionIds)
            .where { (WatchLogsTable.movieId eq movieId) and (WatchLogsTable.visibility eq Visibility.PUBLIC.wireValue) }
            .flatMap { it[WatchLogsTable.emotionIds] }
            .groupingBy { it }.eachCount()
            .entries.sortedWith(compareByDescending<Map.Entry<Short, Int>> { it.value }.thenBy { it.key })
            .mapNotNull { entry -> Emotion.fromId(entry.key.toInt())?.let { EmotionCount(it, entry.value) } }
        MovieCommunity(
            ratings = Rating.entries.associateWith { rating -> ratings.count { it == rating } }.filterValues { it > 0 },
            watched = onPublicShelf(SystemShelfType.WATCHED),
            wantToWatch = onPublicShelf(SystemShelfType.WATCHLIST),
            shelves = shelves,
            emotions = emotions
        )
    }

    override fun activity(movieId: Long, from: LocalDate, until: LocalDate): MovieActivity = transaction {
        val sessionCount = WatchLogsTable.id.count()
        val sessionsByDay = WatchLogsTable.select(WatchLogsTable.watchedOn, sessionCount)
            .where {
                (WatchLogsTable.movieId eq movieId) and (WatchLogsTable.visibility eq Visibility.PUBLIC.wireValue) and
                    (WatchLogsTable.watchedOn greaterEq from) and (WatchLogsTable.watchedOn less until)
            }
            .groupBy(WatchLogsTable.watchedOn)
            .associate { it[WatchLogsTable.watchedOn] to it[sessionCount].toInt() }
        // A rating follows the visibility of Já assisti, as in the community block.
        val publicWatchers = ShelvesTable.select(ShelvesTable.userId).where {
            (ShelvesTable.systemType eq SystemShelfType.WATCHED.wireValue) and
                (ShelvesTable.visibility eq Visibility.PUBLIC.wireValue)
        }
        val ratingsByDay = UserMoviesTable.select(UserMoviesTable.ratedAt)
            .where {
                (UserMoviesTable.movieId eq movieId) and UserMoviesTable.rating.isNotNull() and
                    (UserMoviesTable.ratedAt greaterEq from.atStartOfDayIn(TimeZone.UTC)) and
                    (UserMoviesTable.ratedAt less until.atStartOfDayIn(TimeZone.UTC)) and
                    (UserMoviesTable.userId inSubQuery publicWatchers)
            }
            .mapNotNull { row -> row[UserMoviesTable.ratedAt]?.toLocalDateTime(TimeZone.UTC)?.date }
            .groupingBy { it }.eachCount()
        MovieActivity(sessionsByDay = sessionsByDay, ratingsByDay = ratingsByDay)
    }

    override fun listStates(userId: String, movieIds: List<Long>): Map<Long, MovieListState> = transaction {
        val ids = movieIds.filter { it > 0 }.distinct()
        if (ids.isEmpty()) return@transaction emptyMap()
        val userDbId = userDbIdOf(userId) ?: return@transaction emptyMap()
        UserMoviesTable.select(UserMoviesTable.movieId, UserMoviesTable.status, UserMoviesTable.rating)
            .where { (UserMoviesTable.userId eq userDbId) and (UserMoviesTable.movieId inList ids) }
            .mapNotNull { row ->
                val state = MovieListState(
                    status = WatchStatus.fromWireValue(row[UserMoviesTable.status]),
                    rating = row[UserMoviesTable.rating]?.let { Rating.fromWireValue(it) }
                )
                if (state.status == null && state.rating == null) null
                else row[UserMoviesTable.movieId].value to state
            }
            .toMap()
    }

    // ── Helpers (inside a transaction) ──────────────────────────────────────

    private fun userMovieRow(userDbId: Long, movieId: Long): ResultRow? = UserMoviesTable.selectAll()
        .where { (UserMoviesTable.userId eq userDbId) and (UserMoviesTable.movieId eq movieId) }
        .firstOrNull()

    /** The user_movies row of the pair, created without a situation on the first interaction (ADR rule 1). */
    private fun ensureUserMovie(userDbId: Long, movieId: Long, now: Instant): ResultRow =
        userMovieRow(userDbId, movieId) ?: run {
            UserMoviesTable.insert {
                it[userId] = userDbId
                it[UserMoviesTable.movieId] = movieId
                it[status] = SystemShelves.STATUS_NONE
                it[importSource] = "manual"
                it[createdAt] = now
                it[updatedAt] = now
            }
            userMovieRow(userDbId, movieId)!!
        }

    /** Summarizes the logs of the current cycle into user_movies: furthest point, or 100% once completed. */
    private fun recomputeProgress(userDbId: Long, movieId: Long) {
        val row = userMovieRow(userDbId, movieId) ?: return
        val logs = WatchLogsTable.selectAll()
            .where {
                (WatchLogsTable.userId eq userDbId) and (WatchLogsTable.movieId eq movieId) and
                    (WatchLogsTable.cycle eq row[UserMoviesTable.currentCycle])
            }
            .toList()
        val completed = logs.any { it[WatchLogsTable.completed] }
        val furthestMinutes = logs.mapNotNull { it[WatchLogsTable.minutesWatched] }.maxOrNull()
        val runtime = MoviesTable.select(MoviesTable.runtime).where { MoviesTable.id eq movieId }
            .firstOrNull()?.get(MoviesTable.runtime)
        UserMoviesTable.update({ UserMoviesTable.id eq row[UserMoviesTable.id] }) {
            it[progressMinutes] = if (completed) runtime ?: furthestMinutes else furthestMinutes
            it[progressPercent] = if (completed) 100 else logs.mapNotNull { log -> log[WatchLogsTable.percent] }.maxOrNull()
        }
    }

    /**
     * A completed session puts the movie on Já assisti (and off Quero ver) and clears it as
     * "assistindo agora". The rating label stays: the user changes it by hand.
     */
    private fun applyCompletion(userDbId: Long, movieId: Long, status: String, completed: Boolean, now: Instant) {
        if (!completed) return
        if (status != SystemShelves.STATUS_WATCHED) {
            SystemShelves.setStatus(userDbId, movieId, SystemShelves.STATUS_WATCHED, now)
        }
        UsersTable.update({ (UsersTable.id eq userDbId) and (UsersTable.watchingNowMovieId eq movieId) }) {
            it[watchingNowMovieId] = null
        }
    }

    private fun recentLogs(userDbId: Long): List<ResultRow> = WatchLogsTable.selectAll()
        .where { WatchLogsTable.userId eq userDbId }
        .orderBy(WatchLogsTable.createdAt to SortOrder.DESC, WatchLogsTable.id to SortOrder.DESC)
        .limit(RECENT_LOGS_SCANNED)
        .toList()

    private fun fill(statement: UpdateBuilder<*>, draft: WatchLogDraft) {
        statement[WatchLogsTable.watchedOn] = draft.watchedOn
        statement[WatchLogsTable.mode] = draft.mode.wireValue
        statement[WatchLogsTable.minutesWatched] = draft.minutesWatched
        statement[WatchLogsTable.percent] = draft.percent?.toShort()
        statement[WatchLogsTable.completed] = draft.completed
        statement[WatchLogsTable.venue] = draft.venue?.wireValue
        statement[WatchLogsTable.providerName] = draft.providerName
        statement[WatchLogsTable.emotionIds] = draft.emotions.map { it.id.toShort() }
        statement[WatchLogsTable.gifId] = draft.gif?.id
        statement[WatchLogsTable.gifUrl] = draft.gif?.url
        statement[WatchLogsTable.comment] = draft.comment
        statement[WatchLogsTable.hasSpoiler] = draft.hasSpoiler
        statement[WatchLogsTable.visibility] = draft.visibility.wireValue
    }

    private fun toRecord(row: ResultRow) = UserMovieRecord(
        id = row[UserMoviesTable.id].value,
        status = row[UserMoviesTable.status],
        rating = row[UserMoviesTable.rating]?.let { Rating.fromWireValue(it) },
        review = row[UserMoviesTable.review],
        reviewHasSpoiler = row[UserMoviesTable.reviewHasSpoiler],
        ratedAt = row[UserMoviesTable.ratedAt],
        rewatches = row[UserMoviesTable.rewatches],
        currentCycle = row[UserMoviesTable.currentCycle],
        progressMinutes = row[UserMoviesTable.progressMinutes],
        progressPercent = row[UserMoviesTable.progressPercent]?.toInt()
    )

    private fun toWatchLog(row: ResultRow) = WatchLog(
        id = row[WatchLogsTable.id].value,
        userId = row[UsersTable.externalId],
        movieId = row[WatchLogsTable.movieId].value,
        watchedOn = row[WatchLogsTable.watchedOn],
        cycle = row[WatchLogsTable.cycle],
        mode = WatchMode.fromWireValue(row[WatchLogsTable.mode]) ?: WatchMode.WATCHED,
        minutesWatched = row[WatchLogsTable.minutesWatched],
        percent = row[WatchLogsTable.percent]?.toInt(),
        completed = row[WatchLogsTable.completed],
        venue = row[WatchLogsTable.venue]?.let { WatchVenue.fromWireValue(it) },
        providerName = row[WatchLogsTable.providerName],
        emotions = row[WatchLogsTable.emotionIds].mapNotNull { Emotion.fromId(it.toInt()) },
        gif = row[WatchLogsTable.gifId]?.let { id -> row[WatchLogsTable.gifUrl]?.let { Gif(id, it) } },
        comment = row[WatchLogsTable.comment],
        hasSpoiler = row[WatchLogsTable.hasSpoiler],
        visibility = Visibility.fromWireValue(row[WatchLogsTable.visibility]) ?: Visibility.DEFAULT,
        createdAt = row[WatchLogsTable.createdAt],
        updatedAt = row[WatchLogsTable.updatedAt]
    )

    private companion object {
        const val TOP_EMOTIONS = 3
        const val RECENT_LOGS_SCANNED = 50
    }
}
