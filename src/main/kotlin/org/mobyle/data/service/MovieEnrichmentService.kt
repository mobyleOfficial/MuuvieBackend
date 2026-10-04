package org.mobyle.data.service

import kotlinx.coroutines.*
import org.mobyle.data.local.movies.EnrichmentCandidate
import org.mobyle.data.local.movies.MovieCatalogDataSource
import org.mobyle.data.remote.tmdb.TmdbDataSource
import org.mobyle.data.remote.tmdb.toDomain
import org.slf4j.LoggerFactory

class MovieEnrichmentService(
    private val catalogDataSource: MovieCatalogDataSource,
    private val tmdbDataSource: TmdbDataSource
) {
    private val log = LoggerFactory.getLogger(MovieEnrichmentService::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        private const val BATCH_SIZE = 50
        private const val DELAY_BETWEEN_CALLS_MS = 250L
    }

    suspend fun processBatch(): Int {
        val candidates = catalogDataSource.getMoviesNeedingEnrichment(BATCH_SIZE)
        if (candidates.isEmpty()) return 0

        var enriched = 0
        for (candidate in candidates) {
            try {
                if (candidate.needsResolution) {
                    resolveAndEnrich(candidate)
                } else {
                    enrichFromTmdb(candidate.tmdbId!!)
                }
                enriched++
                delay(DELAY_BETWEEN_CALLS_MS)
            } catch (e: Exception) {
                log.warn("Failed to enrich movie ${candidate.tmdbId} '${candidate.title}': ${e.message}")
            }
        }
        log.info("[ENRICHMENT] Enriched $enriched / ${candidates.size} movies")
        return enriched
    }

    suspend fun enrichOnDemand(tmdbId: Int) {
        enrichFromTmdb(tmdbId)
    }

    private suspend fun resolveAndEnrich(candidate: EnrichmentCandidate) {
        val imdbId = extractImdbId(candidate.imdbUrl)
        if (imdbId == null) {
            log.info("[ENRICHMENT] No IMDb ID for '${candidate.title}' (filmowId=${candidate.filmowId}) — skipping resolution")
            return
        }

        val tmdbId = tmdbDataSource.findByImdbId(imdbId).movieResults.firstOrNull()?.id
        if (tmdbId == null) {
            log.warn("[ENRICHMENT] TMDB has no match for IMDb ID $imdbId ('${candidate.title}')")
            return
        }

        catalogDataSource.resolveScrapedMovie(
            oldDbId = candidate.dbId,
            realTmdbId = tmdbId,
            filmowId = candidate.filmowId
        )

        enrichFromTmdb(tmdbId)
    }

    private fun extractImdbId(imdbUrl: String?): String? {
        if (imdbUrl.isNullOrBlank()) return null
        // Matches tt followed by digits anywhere in the URL, e.g. https://www.imdb.com/title/tt0118694/
        return Regex("(tt\\d+)").find(imdbUrl)?.value
    }

    private suspend fun enrichFromTmdb(tmdbId: Int) {
        val detailResponse = tmdbDataSource.getMovieDetail(tmdbId)
        val detail = detailResponse.toDomain()
        catalogDataSource.enrichMovie(tmdbId, detail, detailResponse.credits)
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
