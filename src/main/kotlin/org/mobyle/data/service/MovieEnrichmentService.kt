package org.mobyle.data.service

import kotlinx.coroutines.*
import org.mobyle.data.local.movies.EnrichmentCandidate
import org.mobyle.data.local.movies.MovieCatalogDataSource
import org.mobyle.data.remote.tmdb.TmdbDataSource
import org.mobyle.data.remote.tmdb.toDomain
import org.mobyle.domain.model.ImportedMovie
import org.mobyle.domain.model.MovieResolution
import org.mobyle.domain.model.vocabulary.ImportSource
import org.mobyle.domain.model.vocabulary.MovieMatchStatus
import org.mobyle.domain.repository.MovieResolver
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Links catalog movies to TMDB and copies the TMDB data into the catalog
 * (filmow-movie-enrichment). Three triggers share [process]: the detail screen ([resolveNow]),
 * the end of an import ([enrichImported]) and the background loop ([processBatch]).
 */
class MovieEnrichmentService(
    private val catalogDataSource: MovieCatalogDataSource,
    private val tmdbDataSource: TmdbDataSource,
    private val resolver: MovieResolver
) {
    private val log = LoggerFactory.getLogger(MovieEnrichmentService::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Movies being processed, so two triggers never resolve the same movie at once.
    private val inFlight = ConcurrentHashMap.newKeySet<Long>()

    companion object {
        private const val BATCH_SIZE = 50
        private const val DELAY_BETWEEN_CALLS_MS = 250L
    }

    suspend fun processBatch(): Int {
        val candidates = catalogDataSource.getMoviesNeedingEnrichment(BATCH_SIZE)
        if (candidates.isEmpty()) return 0

        val results = candidates.map { candidate ->
            process(candidate).also { delay(DELAY_BETWEEN_CALLS_MS) }
        }
        log.info(
            "[ENRICHMENT] Batch of ${candidates.size}: " +
                "matched=${results.count { it == MovieMatchStatus.MATCHED }}, " +
                "unmatched=${results.count { it == MovieMatchStatus.UNMATCHED }}, " +
                "retry later=${results.count { it == MovieMatchStatus.PENDING }}"
        )
        return candidates.size
    }

    suspend fun enrichOnDemand(tmdbId: Int) {
        enrichFromTmdb(tmdbId)
    }

    /** Resolves and enriches one movie right away (the user opened it). */
    suspend fun resolveNow(dbId: Long): MovieMatchStatus {
        val candidate = catalogDataSource.getEnrichmentCandidates(listOf(dbId)).firstOrNull()
            ?: return MovieMatchStatus.PENDING
        // Already tried without a confident match: opening it again does not search again.
        if (candidate.unmatched) return MovieMatchStatus.UNMATCHED
        return process(candidate)
    }

    /** The movies of an import, ahead of the background loop. */
    fun enrichImported(source: ImportSource, externalIds: Collection<String>) {
        if (source != ImportSource.FILMOW || externalIds.isEmpty()) return
        scope.launch {
            val dbIds = catalogDataSource.findDbIdsByFilmowIds(externalIds)
            val pending = catalogDataSource.getEnrichmentCandidates(dbIds).filter { it.needsEnrichment && !it.unmatched }
            log.info("[ENRICHMENT] Import: ${pending.size} of ${dbIds.size} movies to enrich")
            for (candidate in pending) {
                process(candidate)
                delay(DELAY_BETWEEN_CALLS_MS)
            }
        }
    }

    private suspend fun process(candidate: EnrichmentCandidate): MovieMatchStatus {
        if (!inFlight.add(candidate.dbId)) return MovieMatchStatus.PENDING
        return try {
            val tmdbId = candidate.tmdbId
            if (tmdbId != null) {
                enrichFromTmdb(tmdbId)
                return MovieMatchStatus.MATCHED
            }
            when (val resolution = resolver.resolve(candidate.toImportedMovie())) {
                is MovieResolution.Matched -> {
                    catalogDataSource.resolveScrapedMovie(candidate.dbId, resolution.tmdbId, candidate.filmowId)
                    catalogDataSource.markMatched(resolution.tmdbId, resolution.method)
                    log.info("[ENRICHMENT] '${candidate.title}' matched by ${resolution.method.wireValue} -> tmdb ${resolution.tmdbId}")
                    enrichFromTmdb(resolution.tmdbId)
                    MovieMatchStatus.MATCHED
                }
                is MovieResolution.Unmatched -> {
                    catalogDataSource.markUnmatched(candidate.dbId)
                    log.info(
                        "[ENRICHMENT] '${candidate.title}' (${candidate.year ?: "?"}, filmowId=${candidate.filmowId}) " +
                            "unmatched; ${resolution.candidates.size} candidates"
                    )
                    MovieMatchStatus.UNMATCHED
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("[ENRICHMENT] Failed for movie ${candidate.dbId} '${candidate.title}': ${e.message}")
            catalogDataSource.markEnrichmentFailed(candidate.dbId)
            MovieMatchStatus.PENDING
        } finally {
            inFlight.remove(candidate.dbId)
        }
    }

    private suspend fun enrichFromTmdb(tmdbId: Int) {
        val detailResponse = tmdbDataSource.getMovieDetail(tmdbId)
        val detail = detailResponse.toDomain()
        catalogDataSource.enrichMovie(tmdbId, detail, detailResponse.credits)
        catalogDataSource.saveMovieFacts(
            tmdbId,
            detailResponse.originalLanguage,
            detailResponse.productionCountries.mapNotNull { it.iso },
            detailResponse.keywords?.keywords.orEmpty().map { it.id to (it.name ?: "") }
        )
        catalogDataSource.saveSimilarMovies(tmdbId, detail.similarMovies)
        catalogDataSource.saveWatchProviders(tmdbId, detail.watchProviders)
    }

    fun startBackgroundLoop(intervalMinutes: Long = 5) {
        scope.launch {
            delay(30_000) // Wait 30s after startup
            while (isActive) {
                try {
                    val count = processBatch()
                    if (count == 0) {
                        delay(intervalMinutes * 60 * 1000)
                    } else {
                        delay(2000)
                    }
                } catch (e: Exception) {
                    log.error("[ENRICHMENT] Background loop error: ${e.message}")
                    delay(60_000)
                }
            }
        }
    }
}

private fun EnrichmentCandidate.toImportedMovie() = ImportedMovie(
    externalId = filmowId ?: "db:$dbId",
    title = title,
    originalTitle = originalTitle,
    localTitle = localTitle,
    year = year,
    director = director,
    imdbUrl = imdbUrl
)
