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
        val bestMatch = searchWithFallback(candidate)
        if (bestMatch == null) {
            log.warn("[ENRICHMENT] No TMDB match for '${candidate.title}' / '${candidate.originalTitle}' year=${candidate.year} (filmowId=${candidate.filmowId})")
            return
        }

        catalogDataSource.resolveScrapedMovie(
            oldDbId = candidate.dbId,
            realTmdbId = bestMatch,
            filmowId = candidate.filmowId
        )

        enrichFromTmdb(bestMatch)
    }

    private suspend fun searchWithFallback(candidate: EnrichmentCandidate): Int? {
        // 1. Search with title (default language pt-BR)
        val result1 = tmdbDataSource.searchMovies(candidate.title, page = 1, year = candidate.year)
        if (result1.results.isNotEmpty()) return result1.results.first().id

        // 2. Search with originalTitle if different from title
        if (!candidate.originalTitle.isNullOrBlank() && candidate.originalTitle != candidate.title) {
            val result2 = tmdbDataSource.searchMovies(candidate.originalTitle, page = 1, year = candidate.year)
            if (result2.results.isNotEmpty()) return result2.results.first().id
        }

        // 3. Retry title without language filter (multilingual search)
        val result3 = tmdbDataSource.searchMovies(candidate.title, page = 1, year = candidate.year, language = "en-US")
        if (result3.results.isNotEmpty()) return result3.results.first().id

        // 4. Last resort: originalTitle without language filter
        if (!candidate.originalTitle.isNullOrBlank() && candidate.originalTitle != candidate.title) {
            val result4 = tmdbDataSource.searchMovies(candidate.originalTitle, page = 1, year = candidate.year, language = "en-US")
            if (result4.results.isNotEmpty()) return result4.results.first().id
        }

        return null
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
