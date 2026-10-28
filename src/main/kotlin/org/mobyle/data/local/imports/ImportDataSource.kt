package org.mobyle.data.local.imports

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
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

    override fun knownTmdbId(source: ImportSource, externalId: String): Int? = when (source) {
        ImportSource.FILMOW -> catalog.findByFilmowId(externalId)?.tmdbId
        ImportSource.LETTERBOXD -> catalog.findByLetterboxdId(externalId)?.tmdbId
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

        fun step() {
            processed++
            if (processed % PROGRESS_EVERY == 0 || processed == total) onProgress(processed, total)
        }

        fun movieId(movie: ImportedMovie): Long = movieIds.getOrPut(movie.externalId) { ensureMovie(movie, source) }

        for (movie in chosen.watchlist) {
            transaction { if (!upsertUserMovie(userDbId, movieId(movie), movie, source, SystemShelves.STATUS_WANT_TO_WATCH, false)) kept++ }
            step()
        }
        for (movie in chosen.watched) {
            transaction {
                val id = movieId(movie)
                if (!upsertUserMovie(userDbId, id, movie, source, SystemShelves.STATUS_WATCHED, false)) kept++
                if (movie.watchedOn != null && upsertLog(userDbId, id, movie, source, options.defaultVisibility)) logs++
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

        return ImportCounts(
            watched = chosen.watched.size,
            watchlist = chosen.watchlist.size,
            favorites = chosen.favorites.size,
            lists = chosen.lists.size,
            logs = logs,
            unmatched = queued,
            keptFromMuuvie = kept
        )
    }

    /** The catalog movie for the imported one, with the source data the TMDB enrichment uses. */
    private fun ensureMovie(movie: ImportedMovie, source: ImportSource): Long = transaction {
        val filmowId = movie.externalId.takeIf { source == ImportSource.FILMOW }
        val letterboxdId = movie.externalId.takeIf { source == ImportSource.LETTERBOXD }
        var id = catalog.upsertMovie(
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
            if (nextStatus == SystemShelves.STATUS_WATCHED && existing[UserMoviesTable.watchedAt] == null) {
                it[UserMoviesTable.watchedAt] = watchedAt ?: now
            }
            it[importedAt] = now
            it[updatedAt] = now
        }
        if (nextStatus != current) SystemShelves.applyStatus(userDbId, movieDbId, nextStatus, watchedAt ?: now)
        return true
    }

    /** A completed log on the watch date, once per (movie, date, source). Returns false without a date. */
    private fun upsertLog(userDbId: Long, movieDbId: Long, movie: ImportedMovie, source: ImportSource, visibility: Visibility): Boolean {
        val watchedOn = movie.watchedOn ?: return false
        val externalId = "${movie.externalId}@$watchedOn"
        val exists = WatchLogsTable.selectAll()
            .where {
                (WatchLogsTable.userId eq userDbId) and (WatchLogsTable.importSource eq source.wireValue) and
                    (WatchLogsTable.externalId eq externalId)
            }
            .any()
        if (exists) return true
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
        return true
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
        val filmowId = item.movie.externalId.takeIf { source == ImportSource.FILMOW }
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
        const val RESOLUTION_MATCHED = "matched"
        const val RESOLUTION_SKIPPED = "skipped"
        const val PROGRESS_EVERY = 25
        const val NAME_MAX = 255
        const val COVER_MAX = 500
    }
}
