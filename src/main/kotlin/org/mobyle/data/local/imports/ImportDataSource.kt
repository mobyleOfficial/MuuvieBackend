package org.mobyle.data.local.imports

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.between
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNotNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.max
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.mobyle.data.local.database.ImportJobsTable
import org.mobyle.data.local.database.ImportUnmatchedTable
import org.mobyle.data.local.database.MoviesTable
import org.mobyle.data.local.database.ShelvesTable
import org.mobyle.data.local.database.UserMoviesTable
import org.mobyle.data.local.database.UsersTable
import org.mobyle.data.local.database.WatchLogsTable
import org.mobyle.data.local.database.userDbIdOf
import org.mobyle.data.local.movies.MovieCatalogDataSource
import org.mobyle.data.local.shelves.SystemShelves
import org.mobyle.domain.model.ImportBundle
import org.mobyle.domain.model.ImportCounts
import org.mobyle.domain.model.ImportJob
import org.mobyle.domain.model.ImportOptions
import org.mobyle.domain.model.ImportPreview
import org.mobyle.domain.model.ImportedList
import org.mobyle.domain.model.ImportedMovie
import org.mobyle.domain.model.Movie
import org.mobyle.domain.model.vocabulary.MovieMatchMethod
import org.mobyle.domain.model.vocabulary.MovieMatchStatus
import org.mobyle.domain.model.MovieCandidate
import org.mobyle.domain.model.Page
import org.mobyle.domain.model.PageRequest
import org.mobyle.domain.model.UnmatchedItem
import org.mobyle.domain.model.vocabulary.ImportSource
import org.mobyle.domain.model.vocabulary.ImportStatus
import org.mobyle.domain.model.vocabulary.ShelfSort
import org.mobyle.domain.model.vocabulary.Rating
import org.mobyle.domain.model.vocabulary.Visibility
import org.mobyle.domain.model.vocabulary.WatchMode
import org.mobyle.domain.policy.MovieMatchPolicy

/** Import jobs, the confirmation queue and the bundle writer (B3). Users are identified by their external id. */
interface ImportDataSource {
    fun createJob(userId: String, source: ImportSource, inputRef: String): ImportJob?
    fun getJob(jobId: Long): ImportJob?
    fun getJobs(userId: String, page: PageRequest): Page<ImportJob>
    fun hasRunningJob(userId: String): Boolean
    fun transition(jobId: Long, from: Set<ImportStatus>, status: ImportStatus, error: String?): Boolean
    fun saveRead(jobId: Long, bundle: ImportBundle): Boolean
    fun getBundle(jobId: Long): ImportBundle?
    fun confirm(jobId: Long, options: ImportOptions): Boolean
    fun updateProgress(jobId: Long, processed: Int, total: Int)
    fun write(
        job: ImportJob,
        bundle: ImportBundle,
        options: ImportOptions,
        unmatched: Map<String, List<MovieCandidate>>,
        onWritten: (movieDbIds: Collection<Long>) -> Unit = {},
        onProgress: (processed: Int, total: Int) -> Unit
    ): ImportCounts
    fun finish(jobId: Long, counts: ImportCounts)
    fun failInterrupted(): Int
    fun knownTmdbId(source: ImportSource, externalId: String): Int?
    fun getUnmatched(jobId: Long, page: PageRequest): Page<UnmatchedItem>
    fun getUnmatchedItem(itemId: Long): UnmatchedItem?
    fun matchUnmatched(item: UnmatchedItem, source: ImportSource, tmdbId: Int, title: String): UnmatchedItem
    fun skipUnmatched(itemId: Long): UnmatchedItem
}

class ImportDataSourceImpl(private val catalog: MovieCatalogDataSource) : ImportDataSource {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override fun createJob(userId: String, source: ImportSource, inputRef: String): ImportJob? = try {
        transaction {
            val userDbId = userDbIdOf(userId) ?: throw IllegalStateException("User not found")
            val active = ImportJobsTable.selectAll()
                .where {
                    (ImportJobsTable.userId eq userDbId) and (ImportJobsTable.importSource eq source.wireValue) and
                        (ImportJobsTable.status inList ImportStatus.ACTIVE.map { it.wireValue })
                }
                .any()
            if (active) return@transaction null
            val now = Clock.System.now()
            val id = ImportJobsTable.insertAndGetId {
                it[ImportJobsTable.userId] = userDbId
                it[ImportJobsTable.importSource] = source.wireValue
                it[status] = ImportStatus.PENDING.wireValue
                it[ImportJobsTable.inputRef] = inputRef
                it[createdAt] = now
                it[updatedAt] = now
            }.value
            jobRow(id)?.let { toJob(it) }
        }
    } catch (e: ExposedSQLException) {
        // uq_import_job_active: the user already has an active job for this source.
        if (e.sqlState == UNIQUE_VIOLATION) null else throw e
    }

    override fun getJob(jobId: Long): ImportJob? = transaction { jobRow(jobId)?.let { toJob(it) } }

    override fun getJobs(userId: String, page: PageRequest): Page<ImportJob> = transaction {
        val userDbId = userDbIdOf(userId) ?: return@transaction Page.of(emptyList(), page, 0)
        val query = (ImportJobsTable innerJoin UsersTable).selectAll().where { ImportJobsTable.userId eq userDbId }
        val total = query.count().toInt()
        val jobs = query
            .orderBy(ImportJobsTable.createdAt to SortOrder.DESC, ImportJobsTable.id to SortOrder.DESC)
            .limit(page.limit, offset = page.offset)
            .map { toJob(it) }
        Page.of(jobs, page, total)
    }

    override fun hasRunningJob(userId: String): Boolean = transaction {
        (ImportJobsTable innerJoin UsersTable).selectAll()
            .where {
                (UsersTable.externalId eq userId) and
                    (ImportJobsTable.status inList ImportStatus.RUNNING.map { it.wireValue })
            }
            .any()
    }

    override fun transition(jobId: Long, from: Set<ImportStatus>, status: ImportStatus, error: String?): Boolean =
        transaction {
            val now = Clock.System.now()
            ImportJobsTable.update({
                (ImportJobsTable.id eq jobId) and (ImportJobsTable.status inList from.map { it.wireValue })
            }) {
                it[ImportJobsTable.status] = status.wireValue
                if (error != null) it[ImportJobsTable.error] = error
                if (!status.isActive) {
                    it[finishedAt] = now
                    it[bundle] = null
                }
                it[updatedAt] = now
            } > 0
        }

    override fun saveRead(jobId: Long, bundle: ImportBundle): Boolean = transaction {
        ImportJobsTable.update({
            (ImportJobsTable.id eq jobId) and (ImportJobsTable.status eq ImportStatus.READING.wireValue)
        }) {
            it[status] = ImportStatus.PREVIEW.wireValue
            it[ImportJobsTable.bundle] = json.encodeToString(ImportBundle.serializer(), bundle)
            it[preview] = json.encodeToString(ImportPreview.serializer(), bundle.preview())
            it[updatedAt] = Clock.System.now()
        } > 0
    }

    override fun getBundle(jobId: Long): ImportBundle? = transaction {
        ImportJobsTable.select(ImportJobsTable.bundle).where { ImportJobsTable.id eq jobId }
            .firstOrNull()?.get(ImportJobsTable.bundle)
            ?.let { json.decodeFromString(ImportBundle.serializer(), it) }
    }

    override fun confirm(jobId: Long, options: ImportOptions): Boolean = transaction {
        ImportJobsTable.update({
            (ImportJobsTable.id eq jobId) and (ImportJobsTable.status eq ImportStatus.PREVIEW.wireValue)
        }) {
            it[status] = ImportStatus.IMPORTING.wireValue
            it[ImportJobsTable.options] = json.encodeToString(ImportOptions.serializer(), options)
            it[updatedAt] = Clock.System.now()
        } > 0
    }

    override fun updateProgress(jobId: Long, processed: Int, total: Int) {
        transaction {
            ImportJobsTable.update({ ImportJobsTable.id eq jobId }) {
                it[ImportJobsTable.processed] = processed
                it[ImportJobsTable.total] = total
                it[updatedAt] = Clock.System.now()
            }
        }
    }

    override fun finish(jobId: Long, counts: ImportCounts) {
        transaction {
            val now = Clock.System.now()
            ImportJobsTable.update({ ImportJobsTable.id eq jobId }) {
                it[status] = ImportStatus.DONE.wireValue
                it[ImportJobsTable.counts] = json.encodeToString(ImportCounts.serializer(), counts)
                it[bundle] = null
                it[finishedAt] = now
                it[updatedAt] = now
            }
        }
    }

    override fun failInterrupted(): Int = transaction {
        val now = Clock.System.now()
        // A job waiting on its preview survives a restart: its bundle is stored.
        ImportJobsTable.update({
            ImportJobsTable.status inList listOf(
                ImportStatus.PENDING.wireValue, ImportStatus.READING.wireValue, ImportStatus.IMPORTING.wireValue
            )
        }) {
            it[status] = ImportStatus.FAILED.wireValue
            it[error] = INTERRUPTED
            it[bundle] = null
            it[finishedAt] = now
            it[updatedAt] = now
        }
    }

    override fun knownTmdbId(source: ImportSource, externalId: String): Int? = when {
        externalId.startsWith(ImportedMovie.TITLE_KEY_PREFIX) -> null
        source == ImportSource.FILMOW -> catalog.findByFilmowId(externalId)?.tmdbId
        else -> catalog.findByLetterboxdId(externalId)?.tmdbId
    }

    // ── Writer ──────────────────────────────────────────────────────────────

    /**
     * Applies the bundle one movie per transaction, so a large import does not hold a long
     * transaction and the progress can be reported. The order matters: the watchlist goes
     * first so a movie on both ends on Já assisti, which an import never downgrades.
     */
    override fun write(
        job: ImportJob,
        bundle: ImportBundle,
        options: ImportOptions,
        unmatched: Map<String, List<MovieCandidate>>,
        onWritten: (movieDbIds: Collection<Long>) -> Unit,
        onProgress: (processed: Int, total: Int) -> Unit
    ): ImportCounts {
        val source = job.source
        val chosen = bundle.withLists(options.listsToImport?.toSet())
        val total = chosen.watchlist.size + chosen.watched.size + chosen.favorites.size +
            chosen.lists.sumOf { it.movies.size }
        val userDbId = transaction { userDbIdOf(job.userId) } ?: throw IllegalStateException("User not found")
        val movieIds = mutableMapOf<String, Long>()
        var processed = 0
        var kept = 0
        var logs = 0
        var reviews = 0

        fun step() {
            processed++
            if (processed % PROGRESS_EVERY == 0 || processed == total) onProgress(processed, total)
        }

        fun movieId(movie: ImportedMovie): Long = movieIds.getOrPut(movie.externalId) { ensureMovie(movie, source, userDbId) }

        for (movie in chosen.watchlist) {
            transaction { if (!upsertUserMovie(userDbId, movieId(movie), movie, source, SystemShelves.STATUS_WANT_TO_WATCH, false)) kept++ }
            step()
        }
        for (movie in chosen.watched) {
            transaction {
                val id = movieId(movie)
                if (!upsertUserMovie(userDbId, id, movie, source, SystemShelves.STATUS_WATCHED, false)) kept++
                // Every session of the source becomes a log (a Letterboxd diary logs each rewatch).
                logs += movie.sessionDates.groupingBy { it }.eachCount().entries.sumOf { (date, times) ->
                    upsertLogs(userDbId, id, movie, date, times, source, options.defaultVisibility)
                }
                if (movie.sessionDates.size > 1) countRewatches(userDbId, id)
                // A review goes to the rating; without a label to hold it, to the session it was written for.
                if (movie.review != null && (hasReview(userDbId, id, movie.review) || commentSession(userDbId, id, movie))) {
                    reviews++
                }
            }
            step()
        }
        for (movie in chosen.favorites) {
            transaction { if (!upsertUserMovie(userDbId, movieId(movie), movie, source, SystemShelves.STATUS_WATCHED, true)) kept++ }
            step()
        }
        for (list in chosen.lists) {
            val shelfId = transaction { ensureShelf(userDbId, list, source, options.defaultVisibility) }
            for (movie in list.movies) {
                transaction { SystemShelves.addItem(shelfId, movieId(movie), Clock.System.now()) }
                step()
            }
        }

        val queued = transaction {
            unmatched.count { (externalId, candidates) ->
                val movie = chosen.distinctMovies.firstOrNull { it.externalId == externalId } ?: return@count false
                queueUnmatched(job, userDbId, movie, candidates, movieIds[externalId])
                true
            }
        }

        onWritten(movieIds.values.distinct())
        return ImportCounts(
            watched = chosen.watched.size,
            watchlist = chosen.watchlist.size,
            favorites = chosen.favorites.size,
            lists = chosen.lists.size,
            logs = logs,
            unmatched = queued,
            keptFromMuuvie = kept,
            reviews = reviews
        )
    }

    /** The catalog movie for the imported one, with the source data the TMDB enrichment uses. */
    private fun ensureMovie(movie: ImportedMovie, source: ImportSource, userDbId: Long? = null): Long = transaction {
        // A title key (title and year) is not an id on the source and is never stored as one.
        val sourceId = movie.externalId.takeUnless { it.startsWith(ImportedMovie.TITLE_KEY_PREFIX) }
        val filmowId = sourceId.takeIf { source == ImportSource.FILMOW }
        val letterboxdId = sourceId.takeIf { source == ImportSource.LETTERBOXD }
        val knownBySourceId = when {
            filmowId != null -> MoviesTable.select(MoviesTable.id).where { MoviesTable.filmowId eq filmowId }.any()
            letterboxdId != null -> MoviesTable.select(MoviesTable.id).where { MoviesTable.letterboxdId eq letterboxdId }.any()
            else -> false
        }
        val placeholder = if (movie.tmdbId == null && !knownBySourceId) findPlaceholder(movie, userDbId) else null
        val hasSourceData = filmowId != null || letterboxdId != null || movie.posterUrl != null ||
            movie.originalTitle != null || movie.localTitle != null
        if (placeholder != null && hasSourceData) {
            // The same movie from an earlier import: the last import takes it over.
            MoviesTable.update({ MoviesTable.id eq placeholder }) { row ->
                if (filmowId != null) row[MoviesTable.filmowId] = filmowId
                if (letterboxdId != null) row[MoviesTable.letterboxdId] = letterboxdId
                movie.posterUrl?.let { row[posterPath] = it }
                movie.originalTitle?.let { row[originalTitle] = it }
                movie.localTitle?.let { row[localTitle] = it }
            }
        }
        var id = placeholder ?: catalog.upsertMovie(
            movie = Movie(
                tmdbId = movie.tmdbId,
                title = movie.title,
                originalTitle = movie.originalTitle,
                localTitle = movie.localTitle,
                overview = movie.overview.orEmpty(),
                posterPath = movie.posterUrl,
                releaseDate = movie.releaseDate ?: movie.year?.toString()
            ),
            filmowId = filmowId,
            letterboxdId = letterboxdId
        )
        // A placeholder from an earlier import that TMDB now matched: merge it with the real movie.
        val currentTmdbId = MoviesTable.select(MoviesTable.tmdbId).where { MoviesTable.id eq id }.first()[MoviesTable.tmdbId]
        if (movie.tmdbId != null && currentTmdbId == null) {
            catalog.resolveScrapedMovie(id, movie.tmdbId, filmowId)
            id = MoviesTable.select(MoviesTable.id).where { MoviesTable.tmdbId eq movie.tmdbId }.first()[MoviesTable.id].value
        }
        if (movie.tmdbId != null) {
            catalog.markMatched(movie.tmdbId, MovieMatchMethod.fromWireValue(movie.matchMethod) ?: MovieMatchMethod.TITLE)
        }
        if (movie.imdbUrl != null || movie.runtime != null || movie.director != null || movie.genres.isNotEmpty()) {
            MoviesTable.update({ MoviesTable.id eq id }) { row ->
                // New source data gives an unmatched placeholder another chance in the queue.
                if (currentTmdbId == null && movie.tmdbId == null) {
                    row[matchStatus] = MovieMatchStatus.PENDING.wireValue
                    row[needsEnrichment] = true
                }
                movie.imdbUrl?.let { row[imdbUrl] = it }
                movie.runtime?.let { row[runtime] = it }
                movie.director?.let { row[director] = it }
                if (movie.genres.isNotEmpty()) row[filmowGenres] = movie.genres.joinToString(",")
            }
        }
        id
    }

    /**
     * A placeholder (no TMDB id) an earlier import made for the same title and year (±1), from any
     * source: the Filmow login and the Filmow CSV, or Filmow and Letterboxd, name the same movie
     * without sharing an id. Reusing it keeps one movie instead of two, and keeps file reimports
     * idempotent for movies keyed by title. The user's own placeholder comes first.
     */
    private fun findPlaceholder(movie: ImportedMovie, userDbId: Long?): Long? {
        val key = MovieMatchPolicy.normalize(movie.title).takeIf { it.isNotEmpty() } ?: return null
        val year = movie.year
        // Filmow and Letterboxd sometimes disagree on the year by one (festival vs release).
        val nearYear = if (year != null) MoviesTable.year.between(year - 1, year + 1) else MoviesTable.year.isNull()
        val candidates = MoviesTable
            .select(MoviesTable.id, MoviesTable.year, MoviesTable.title, MoviesTable.originalTitle, MoviesTable.localTitle)
            .where { MoviesTable.tmdbId.isNull() and nearYear }
            .filter { row ->
                listOfNotNull(row[MoviesTable.title], row[MoviesTable.originalTitle], row[MoviesTable.localTitle])
                    .any { MovieMatchPolicy.normalize(it) == key }
            }
        if (candidates.isEmpty()) return null
        val owned = if (userDbId == null) emptySet() else UserMoviesTable.select(UserMoviesTable.movieId)
            .where { (UserMoviesTable.userId eq userDbId) and (UserMoviesTable.movieId inList candidates.map { it[MoviesTable.id].value }) }
            .map { it[UserMoviesTable.movieId].value }
            .toSet()
        // The same year first, then the user's own, then the oldest.
        return candidates.minWith(
            compareBy<ResultRow>(
                { if (it[MoviesTable.year] == year) 0 else 1 },
                { if (it[MoviesTable.id].value in owned) 0 else 1 },
                { it[MoviesTable.id].value }
            )
        )[MoviesTable.id].value
    }

    /** Whether the review was kept: it belongs to the rating, so a movie without a label has none. */
    private fun hasReview(userDbId: Long, movieDbId: Long, review: String): Boolean =
        UserMoviesTable.select(UserMoviesTable.review)
            .where { (UserMoviesTable.userId eq userDbId) and (UserMoviesTable.movieId eq movieDbId) }
            .firstOrNull()?.get(UserMoviesTable.review) == review.trim().take(REVIEW_MAX_LENGTH)

    /**
     * Each imported session is one viewing: in date order they take cycles 1, 2, 3… and the
     * movie counts the rewatches, as if logged in the app. Only for a movie the import owns,
     * whose sessions all came from imports, and on which the user has not started a new cycle
     * (that would be a rewatch begun in the app); otherwise the cycles stay as they are.
     */
    private fun countRewatches(userDbId: Long, movieDbId: Long) {
        val userMovie = UserMoviesTable.selectAll()
            .where { (UserMoviesTable.userId eq userDbId) and (UserMoviesTable.movieId eq movieDbId) }
            .firstOrNull() ?: return
        if (userMovie[UserMoviesTable.importSource] == MANUAL) return
        val sessions = WatchLogsTable.select(WatchLogsTable.id, WatchLogsTable.watchedOn, WatchLogsTable.cycle, WatchLogsTable.importSource)
            .where { (WatchLogsTable.userId eq userDbId) and (WatchLogsTable.movieId eq movieDbId) }
            .toList()
        if (sessions.isEmpty() || sessions.any { it[WatchLogsTable.importSource] == null }) return
        if (userMovie[UserMoviesTable.currentCycle] > sessions.maxOf { it[WatchLogsTable.cycle] }) return
        val ordered = sessions.sortedWith(compareBy({ it[WatchLogsTable.watchedOn] }, { it[WatchLogsTable.id].value }))
        ordered.forEachIndexed { index, session ->
            if (session[WatchLogsTable.cycle] != index + 1) {
                WatchLogsTable.update({ WatchLogsTable.id eq session[WatchLogsTable.id] }) { it[cycle] = index + 1 }
            }
        }
        UserMoviesTable.update({ UserMoviesTable.id eq userMovie[UserMoviesTable.id] }) {
            it[currentCycle] = ordered.size
            it[rewatches] = ordered.size - 1
        }
    }

    /**
     * Puts a review that has no rating to hold it on an imported session: the one of the review's
     * date (on Letterboxd every review belongs to a diary entry), else the latest. Without any
     * imported session there is nowhere to put it. Returns whether it was kept.
     */
    private fun commentSession(userDbId: Long, movieDbId: Long, movie: ImportedMovie): Boolean {
        val comment = movie.review?.trim()?.takeIf { it.isNotEmpty() }?.take(COMMENT_MAX_LENGTH) ?: return false
        val imported = WatchLogsTable.select(WatchLogsTable.id, WatchLogsTable.watchedOn)
            .where {
                (WatchLogsTable.userId eq userDbId) and (WatchLogsTable.movieId eq movieDbId) and
                    WatchLogsTable.importSource.isNotNull()
            }
            .map { it[WatchLogsTable.id].value to it[WatchLogsTable.watchedOn] }
        val target = imported.firstOrNull { (_, watchedOn) -> watchedOn == movie.reviewOn }
            ?: imported.maxByOrNull { (id, watchedOn) -> watchedOn.toEpochDays().toLong() * Int.MAX_VALUE + id }
            ?: return false
        WatchLogsTable.update({ WatchLogsTable.id eq target.first }) {
            it[WatchLogsTable.comment] = comment
            it[updatedAt] = Clock.System.now()
        }
        return true
    }

    /**
     * One user_movies row per pair. A row the user owns (source manual) keeps everything and
     * only gets empty fields filled; returns false in that case. An imported row follows the
     * source, except that a watched movie is never moved back to the watchlist and a
     * favorite is never downgraded. The heart becomes Favoritado, the stars a label (B13.1).
     */
    private fun upsertUserMovie(
        userDbId: Long,
        movieDbId: Long,
        movie: ImportedMovie,
        source: ImportSource,
        status: String,
        favorite: Boolean
    ): Boolean {
        val now = Clock.System.now()
        val label = if (favorite) Rating.FAVORITE.wireValue else movie.stars?.takeIf { it > 0 }?.let { Rating.fromStars(it).wireValue }
        val watchedAt = movie.watchedOn?.atTime(LocalTime(0, 0))?.toInstant(TimeZone.UTC)
        val review = movie.review?.trim()?.takeIf { it.isNotEmpty() }?.take(REVIEW_MAX_LENGTH)
        val existing = UserMoviesTable.selectAll()
            .where { (UserMoviesTable.userId eq userDbId) and (UserMoviesTable.movieId eq movieDbId) }
            .firstOrNull()

        if (existing == null) {
            UserMoviesTable.insert {
                it[userId] = userDbId
                it[movieId] = movieDbId
                it[UserMoviesTable.status] = status
                it[rating] = label
                if (label != null) it[ratedAt] = now
                // The review belongs to the rating; without a label there is nothing to attach it to.
                if (label != null && review != null) it[UserMoviesTable.review] = review
                if (status == SystemShelves.STATUS_WATCHED) it[UserMoviesTable.watchedAt] = watchedAt ?: now
                it[importSource] = source.wireValue
                it[importedAt] = now
                it[createdAt] = now
                it[updatedAt] = now
            }
            SystemShelves.applyStatus(userDbId, movieDbId, status, watchedAt ?: now)
            return true
        }

        if (existing[UserMoviesTable.importSource] == MANUAL) {
            UserMoviesTable.update({ UserMoviesTable.id eq existing[UserMoviesTable.id] }) {
                if (existing[UserMoviesTable.rating] == null && label != null) {
                    it[rating] = label
                    it[ratedAt] = now
                }
                val hasLabel = existing[UserMoviesTable.rating] != null || label != null
                if (existing[UserMoviesTable.review] == null && review != null && hasLabel) it[UserMoviesTable.review] = review
                if (existing[UserMoviesTable.watchedAt] == null && watchedAt != null) it[UserMoviesTable.watchedAt] = watchedAt
                it[importedAt] = now
            }
            return false
        }

        val current = existing[UserMoviesTable.status]
        val nextStatus = if (current == SystemShelves.STATUS_WATCHED) current else status
        UserMoviesTable.update({ UserMoviesTable.id eq existing[UserMoviesTable.id] }) {
            it[UserMoviesTable.status] = nextStatus
            if (label != null && existing[UserMoviesTable.rating] != Rating.FAVORITE.wireValue) {
                it[rating] = label
                if (existing[UserMoviesTable.ratedAt] == null) it[ratedAt] = now
            }
            // An imported row follows the last import: its review and its watch date win.
            if (review != null && (label != null || existing[UserMoviesTable.rating] != null)) it[UserMoviesTable.review] = review
            if (nextStatus == SystemShelves.STATUS_WATCHED && (watchedAt != null || existing[UserMoviesTable.watchedAt] == null)) {
                it[UserMoviesTable.watchedAt] = watchedAt ?: now
            }
            it[UserMoviesTable.importSource] = source.wireValue
            it[importedAt] = now
            it[updatedAt] = now
        }
        if (nextStatus != current) SystemShelves.applyStatus(userDbId, movieDbId, nextStatus, watchedAt ?: now)
        return true
    }

    /** A completed log on the watch date, once per (movie, date, source). Returns false without a date. */
    /**
     * [times] completed sessions of the movie on [watchedOn]. Imported sessions of that day are
     * shared by every import and source: only the ones missing are added, so a reimport adds
     * none. Returns [times].
     */
    private fun upsertLogs(
        userDbId: Long,
        movieDbId: Long,
        movie: ImportedMovie,
        watchedOn: LocalDate,
        times: Int,
        source: ImportSource,
        visibility: Visibility
    ): Int {
        val existing = WatchLogsTable.selectAll()
            .where {
                (WatchLogsTable.userId eq userDbId) and (WatchLogsTable.movieId eq movieDbId) and
                    (WatchLogsTable.watchedOn eq watchedOn) and WatchLogsTable.importSource.isNotNull()
            }
            .count().toInt()
        for (session in existing + 1..times) {
            // The first session of the day keeps the id earlier versions wrote.
            val externalId = "${movie.externalId}@$watchedOn" + if (session > 1) "#$session" else ""
            insertLog(userDbId, movieDbId, watchedOn, externalId, source, visibility)
        }
        return times
    }

    private fun insertLog(
        userDbId: Long,
        movieDbId: Long,
        watchedOn: LocalDate,
        externalId: String,
        source: ImportSource,
        visibility: Visibility
    ) {
        val userMovie = UserMoviesTable.selectAll()
            .where { (UserMoviesTable.userId eq userDbId) and (UserMoviesTable.movieId eq movieDbId) }
            .first()
        val now = Clock.System.now()
        WatchLogsTable.insert {
            it[userId] = userDbId
            it[movieId] = movieDbId
            it[WatchLogsTable.watchedOn] = watchedOn
            it[cycle] = userMovie[UserMoviesTable.currentCycle]
            it[mode] = WatchMode.WATCHED.wireValue
            it[percent] = 100
            it[completed] = true
            it[WatchLogsTable.visibility] = visibility.wireValue
            it[importSource] = source.wireValue
            it[WatchLogsTable.externalId] = externalId
            it[createdAt] = now
            it[updatedAt] = now
        }
        // The user's own progress is left alone; an imported row is complete.
        if (userMovie[UserMoviesTable.importSource] != MANUAL) {
            val runtime = MoviesTable.select(MoviesTable.runtime).where { MoviesTable.id eq movieDbId }.first()[MoviesTable.runtime]
            UserMoviesTable.update({ UserMoviesTable.id eq userMovie[UserMoviesTable.id] }) {
                it[progressPercent] = 100
                it[progressMinutes] = runtime
            }
        }
    }

    /** The shelf of the imported list, created on the first import. Its name and settings stay the user's. */
    private fun ensureShelf(userDbId: Long, list: ImportedList, source: ImportSource, visibility: Visibility): Long {
        ShelvesTable.select(ShelvesTable.id)
            .where {
                (ShelvesTable.userId eq userDbId) and (ShelvesTable.importSource eq source.wireValue) and
                    (ShelvesTable.externalId eq list.externalId)
            }
            .firstOrNull()
            ?.let { return it[ShelvesTable.id].value }
        val now = Clock.System.now()
        val lastPosition = ShelvesTable.select(ShelvesTable.position.max())
            .where { ShelvesTable.userId eq userDbId }
            .firstOrNull()?.get(ShelvesTable.position.max()) ?: -1
        return ShelvesTable.insertAndGetId {
            it[userId] = userDbId
            it[name] = list.title.take(NAME_MAX)
            it[description] = list.description
            it[ShelvesTable.visibility] = visibility.wireValue
            it[position] = lastPosition + 1
            it[sortMode] = ShelfSort.MANUAL.wireValue
            it[importSource] = source.wireValue
            it[externalId] = list.externalId
            it[coverUrl] = list.coverUrl?.take(COVER_MAX)
            it[createdAt] = now
            it[updatedAt] = now
        }.value
    }

    /** Queues the movie once per user and source: a reimport does not ask again. */
    private fun queueUnmatched(job: ImportJob, userDbId: Long, movie: ImportedMovie, candidates: List<MovieCandidate>, movieDbId: Long?) {
        val alreadyQueued = (ImportUnmatchedTable innerJoin ImportJobsTable).selectAll()
            .where {
                (ImportJobsTable.userId eq userDbId) and (ImportJobsTable.importSource eq job.source.wireValue) and
                    (ImportUnmatchedTable.externalId eq movie.externalId)
            }
            .any()
        if (alreadyQueued) return
        // The same movie waiting from an earlier import: the last one replaces it.
        if (movieDbId != null) {
            val earlier = (ImportUnmatchedTable innerJoin ImportJobsTable)
                .select(ImportUnmatchedTable.id)
                .where {
                    (ImportJobsTable.userId eq userDbId) and (ImportUnmatchedTable.movieId eq movieDbId) and
                        (ImportUnmatchedTable.resolution eq RESOLUTION_PENDING)
                }
                .map { it[ImportUnmatchedTable.id].value }
            if (earlier.isNotEmpty()) {
                ImportUnmatchedTable.update({ ImportUnmatchedTable.id inList earlier }) { it[resolution] = RESOLUTION_SKIPPED }
            }
        }
        ImportUnmatchedTable.insert {
            it[jobId] = job.id
            it[externalId] = movie.externalId
            it[raw] = json.encodeToString(ImportedMovie.serializer(), movie)
            it[ImportUnmatchedTable.candidates] = json.encodeToString(ListSerializer(MovieCandidate.serializer()), candidates)
            it[movieId] = movieDbId
            it[createdAt] = Clock.System.now()
        }
    }

    // ── Confirmation queue ──────────────────────────────────────────────────

    override fun getUnmatched(jobId: Long, page: PageRequest): Page<UnmatchedItem> = transaction {
        val query = ImportUnmatchedTable.selectAll().where { ImportUnmatchedTable.jobId eq jobId }
        val total = query.count().toInt()
        val items = query
            .orderBy(ImportUnmatchedTable.id to SortOrder.ASC)
            .limit(page.limit, offset = page.offset)
            .map { toUnmatched(it) }
        Page.of(items, page, total)
    }

    override fun getUnmatchedItem(itemId: Long): UnmatchedItem? = transaction {
        ImportUnmatchedTable.selectAll().where { ImportUnmatchedTable.id eq itemId }.firstOrNull()?.let { toUnmatched(it) }
    }

    override fun matchUnmatched(item: UnmatchedItem, source: ImportSource, tmdbId: Int, title: String): UnmatchedItem = transaction {
        val filmowId = item.movie.externalId
            .takeIf { source == ImportSource.FILMOW && !it.startsWith(ImportedMovie.TITLE_KEY_PREFIX) }
        val placeholderId = item.movieId ?: ensureMovie(item.movie, source)
        catalog.resolveScrapedMovie(placeholderId, tmdbId, filmowId)
        val movieDbId = MoviesTable.select(MoviesTable.id).where { MoviesTable.tmdbId eq tmdbId }.firstOrNull()
            ?.get(MoviesTable.id)?.value
            ?: catalog.upsertMovie(Movie(tmdbId = tmdbId, title = title))
        MoviesTable.update({ MoviesTable.id eq movieDbId }) { it[needsEnrichment] = true }
        catalog.markMatched(tmdbId, MovieMatchMethod.MANUAL)
        ImportUnmatchedTable.update({ ImportUnmatchedTable.id eq item.id }) {
            it[resolution] = RESOLUTION_MATCHED
            it[movieId] = movieDbId
        }
        getUnmatchedItem(item.id)!!
    }

    override fun skipUnmatched(itemId: Long): UnmatchedItem = transaction {
        ImportUnmatchedTable.update({ ImportUnmatchedTable.id eq itemId }) { it[resolution] = RESOLUTION_SKIPPED }
        getUnmatchedItem(itemId)!!
    }

    // ── Mapping ─────────────────────────────────────────────────────────────

    private fun jobRow(jobId: Long): ResultRow? =
        (ImportJobsTable innerJoin UsersTable).selectAll().where { ImportJobsTable.id eq jobId }.firstOrNull()

    private fun toJob(row: ResultRow) = ImportJob(
        id = row[ImportJobsTable.id].value,
        source = ImportSource.fromWireValue(row[ImportJobsTable.importSource])!!,
        status = ImportStatus.fromWireValue(row[ImportJobsTable.status])!!,
        inputRef = row[ImportJobsTable.inputRef],
        processed = row[ImportJobsTable.processed],
        total = row[ImportJobsTable.total],
        preview = row[ImportJobsTable.preview]?.let { json.decodeFromString(ImportPreview.serializer(), it) },
        options = row[ImportJobsTable.options]?.let { json.decodeFromString(ImportOptions.serializer(), it) },
        counts = row[ImportJobsTable.counts]?.let { json.decodeFromString(ImportCounts.serializer(), it) },
        error = row[ImportJobsTable.error],
        createdAt = row[ImportJobsTable.createdAt],
        updatedAt = row[ImportJobsTable.updatedAt],
        finishedAt = row[ImportJobsTable.finishedAt],
        userId = row[UsersTable.externalId]
    )

    private fun toUnmatched(row: ResultRow) = UnmatchedItem(
        id = row[ImportUnmatchedTable.id].value,
        jobId = row[ImportUnmatchedTable.jobId].value,
        movie = json.decodeFromString(ImportedMovie.serializer(), row[ImportUnmatchedTable.raw]),
        candidates = json.decodeFromString(ListSerializer(MovieCandidate.serializer()), row[ImportUnmatchedTable.candidates]),
        resolution = row[ImportUnmatchedTable.resolution],
        movieId = row[ImportUnmatchedTable.movieId]?.value
    )

    private companion object {
        const val MANUAL = "manual"
        const val UNIQUE_VIOLATION = "23505"
        const val INTERRUPTED = "Interrupted by a server restart"
        const val RESOLUTION_PENDING = "pending"
        const val RESOLUTION_MATCHED = "matched"
        // Same limit as a review written in the app (SetMovieRating).
        const val REVIEW_MAX_LENGTH = 10_000
        // Same limit as a session comment written in the app (WatchLogInput).
        const val COMMENT_MAX_LENGTH = 5_000
        const val RESOLUTION_SKIPPED = "skipped"
        const val PROGRESS_EVERY = 25
        const val NAME_MAX = 255
        const val COVER_MAX = 500
    }
}
