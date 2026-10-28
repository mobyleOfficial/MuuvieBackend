package org.mobyle.data.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.mobyle.domain.model.ImportBundle
import org.mobyle.domain.model.ImportJob
import org.mobyle.domain.model.ImportOptions
import org.mobyle.domain.model.ImportedMovie
import org.mobyle.domain.model.MovieCandidate
import org.mobyle.domain.model.MovieResolution
import org.mobyle.domain.model.SocialEvent
import org.mobyle.domain.model.vocabulary.MovieMatchMethod
import org.mobyle.domain.model.vocabulary.ImportSource
import org.mobyle.domain.model.vocabulary.ImportStatus
import org.mobyle.domain.repository.ImportJobQueue
import org.mobyle.domain.repository.ImportRepository
import org.mobyle.domain.repository.ImportSourceReader
import org.mobyle.domain.repository.MovieResolver
import org.mobyle.domain.repository.SocialNotifier
import org.mobyle.domain.usecase.feed.ActivityPublisher
import org.slf4j.LoggerFactory

/**
 * Runs import jobs in the background, at most [MAX_CONCURRENT] at a time (B3). The state lives
 * in import_jobs, so the app can poll it; each change is also pushed on the user's WebSocket as
 * `import_progress` with the job id. When a job waits for the preview, finishes or fails, the
 * user also gets an `import_updated` through the [SocialNotifier], which reaches them by push.
 */
class ImportJobRunner(
    private val repository: ImportRepository,
    private val readers: List<ImportSourceReader>,
    private val resolver: MovieResolver,
    private val enrichment: MovieEnrichmentService,
    private val webSocketManager: WebSocketManager,
    private val activities: ActivityPublisher,
    private val notifier: SocialNotifier
) : ImportJobQueue {
    private val log = LoggerFactory.getLogger(ImportJobRunner::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val slots = Semaphore(MAX_CONCURRENT)

    override fun read(job: ImportJob, credentials: String, autoConfirm: Boolean) {
        run(job, READ_FAILED) {
            if (!repository.transition(job.id, setOf(ImportStatus.PENDING), ImportStatus.READING)) return@run
            notify(job.id)
            log.info(
                "[IMPORT] Job ${job.id} reading ${job.source.wireValue} @${job.inputRef} " +
                    "(credentials: ${if (credentials.isBlank()) "none" else "${credentials.length} chars"})"
            )
            val reader = readers.first { it.source == job.source }
            val bundle = reader.read(job.inputRef, credentials)
            log.info(
                "[IMPORT] Job ${job.id} read: watched=${bundle.watched.size}, watchlist=${bundle.watchlist.size}, " +
                    "favorites=${bundle.favorites.size}, lists=${bundle.lists.size}"
            )
            // False when the user cancelled while the source was being read: the bundle is dropped.
            if (!repository.saveRead(job.id, bundle)) return@run
            notify(job.id)
            if (!autoConfirm) announce(job.id)
            if (autoConfirm && repository.confirm(job.id, ImportOptions())) {
                writeJob(repository.getJob(job.id)!!)
            }
        }
    }

    override fun write(job: ImportJob) {
        run(job, WRITE_FAILED) { writeJob(job) }
    }

    private suspend fun writeJob(job: ImportJob) {
        val bundle = repository.getBundle(job.id) ?: throw IllegalStateException("Job ${job.id} has no bundle")
        val options = job.options ?: ImportOptions()
        val chosen = bundle.withLists(options.listsToImport?.toSet())
        val (matched, unmatched) = match(job.source, chosen.distinctMovies)
        val counts = repository.write(job, withTmdbIds(chosen, matched), options, unmatched) { processed, total ->
            repository.updateProgress(job.id, processed, total)
            scope.launch { notify(job.id) }
        }
        repository.finish(job.id, counts)
        notify(job.id)
        announce(job.id)
        // The imported movies are enriched now instead of waiting for the background loop.
        enrichment.enrichImported(job.source, chosen.distinctMovies.map { it.externalId })
        activities.moviesImported(job.userId, job.id, job.source, counts.watched, counts.watchlist)
        log.info("[IMPORT] Job ${job.id} done: $counts")
    }

    /**
     * Movies with an IMDb link, or already known to the catalog, are matched later by the
     * enrichment. The others go through the resolver (title and year, then director): a
     * confident match is used, the rest is queued for the user.
     */
    private suspend fun match(
        source: ImportSource,
        movies: List<ImportedMovie>
    ): Pair<Map<String, MovieResolution.Matched>, Map<String, List<MovieCandidate>>> {
        val matched = mutableMapOf<String, MovieResolution.Matched>()
        val unmatched = mutableMapOf<String, List<MovieCandidate>>()
        for (movie in movies) {
            if (movie.tmdbId != null || movie.imdbUrl != null) continue
            if (repository.knownTmdbId(source, movie.externalId) != null) continue
            val resolution = try {
                resolver.resolve(movie)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // TMDB unavailable: the movie is imported as it is and the enrichment retries later.
                log.warn("[IMPORT] TMDB search failed for '${movie.title}': ${e.message}")
                continue
            }
            when (resolution) {
                is MovieResolution.Matched -> matched[movie.externalId] = resolution
                is MovieResolution.Unmatched -> unmatched[movie.externalId] = resolution.candidates
            }
            delay(SEARCH_INTERVAL_MS)
        }
        log.info(
            "[IMPORT] Matching: ${matched.size} matched (" +
                MovieMatchMethod.entries.joinToString { m -> "${m.wireValue}=${matched.values.count { it.method == m }}" } +
                "), ${unmatched.size} to confirm"
        )
        return matched to unmatched
    }

    private fun withTmdbIds(bundle: ImportBundle, matched: Map<String, MovieResolution.Matched>): ImportBundle {
        if (matched.isEmpty()) return bundle
        fun ImportedMovie.resolved() =
            matched[externalId]?.let { copy(tmdbId = it.tmdbId, matchMethod = it.method.wireValue) } ?: this
        return bundle.copy(
            watched = bundle.watched.map { it.resolved() },
            watchlist = bundle.watchlist.map { it.resolved() },
            favorites = bundle.favorites.map { it.resolved() },
            lists = bundle.lists.map { list -> list.copy(movies = list.movies.map { it.resolved() }) }
        )
    }

    private fun run(job: ImportJob, failure: String, block: suspend () -> Unit) {
        scope.launch {
            slots.withPermit {
                try {
                    block()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.error("[IMPORT] Job ${job.id} failed (${job.source.wireValue} @${job.inputRef}): ${e.message}", e)
                    repository.transition(job.id, ImportStatus.ACTIVE, ImportStatus.FAILED, failure)
                    notify(job.id)
                    announce(job.id)
                }
            }
        }
    }

    private suspend fun notify(jobId: Long) {
        val job = repository.getJob(jobId) ?: return
        webSocketManager.send(
            job.userId,
            WsMessage(
                type = MESSAGE_TYPE,
                payload = mapOf(
                    "jobId" to job.id.toString(),
                    "source" to job.source.wireValue,
                    "status" to job.status.wireValue,
                    "processed" to job.processed.toString(),
                    "total" to job.total.toString()
                )
            )
        )
    }

    /** Tells the user an import stopped running (preview, done or failed), by push too (B15.2). */
    private fun announce(jobId: Long) {
        val job = repository.getJob(jobId) ?: return
        val watched = when (job.status) {
            ImportStatus.PREVIEW -> job.preview?.watched ?: 0
            ImportStatus.DONE -> job.counts?.watched ?: 0
            ImportStatus.FAILED -> 0
            else -> return
        }
        notifier.notify(job.userId, SocialEvent.ImportUpdated(job.id, job.source.wireValue, job.status.wireValue, watched))
    }

    private companion object {
        const val MAX_CONCURRENT = 2
        const val SEARCH_INTERVAL_MS = 100L
        const val MESSAGE_TYPE = "import_progress"
        // Shown to the user: never the internal cause.
        const val READ_FAILED = "Could not read the source profile"
        const val WRITE_FAILED = "Could not finish the import"
    }
}
