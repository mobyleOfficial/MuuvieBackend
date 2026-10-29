package org.mobyle.data.local.movies

import org.mobyle.domain.model.FilmowMoviePartial
import org.mobyle.domain.model.Movie
import org.mobyle.domain.model.MovieDetail
import org.mobyle.domain.model.WatchProvider
import org.mobyle.data.remote.tmdb.model.TmdbCredits
import org.mobyle.domain.model.vocabulary.MovieMatchMethod

interface MovieCatalogDataSource {
    fun findById(id: Long): Movie?
    fun findByTmdbId(tmdbId: Int): Movie?
    fun findByFilmowId(filmowId: String): Movie?
    fun findByLetterboxdId(letterboxdId: String): Movie?
    fun getDbIdByFilmowId(filmowId: String): Long?
    fun upsertMovie(movie: Movie, filmowId: String? = null, letterboxdId: String? = null): Long
    fun enrichMovie(tmdbId: Int, detail: MovieDetail, credits: TmdbCredits?)
    /** Whether the movie keeps an overview in Portuguese, original or machine translated. */
    fun hasPortugueseOverview(tmdbId: Int): Boolean
    /** Original language, production countries and keywords, read by the maratona challenges (B7). */
    fun saveMovieFacts(tmdbId: Int, originalLanguage: String?, countries: List<String>, keywords: List<Pair<Int, String>>)
    fun saveSimilarMovies(tmdbId: Int, similars: List<Movie>)
    fun saveWatchProviders(tmdbId: Int, providers: List<WatchProvider>)
    fun getSimilarMovies(tmdbId: Int): CachedData<List<Movie>>
    fun getWatchProviders(tmdbId: Int): CachedData<List<WatchProvider>>
    fun resolveScrapedMovie(oldDbId: Long, realTmdbId: Int, filmowId: String?)
    /** The enrichment queue: pending or stale movies, skipping unmatched ones and those in backoff. */
    fun getMoviesNeedingEnrichment(limit: Int = 50): List<EnrichmentCandidate>
    fun getEnrichmentCandidates(dbIds: Collection<Long>): List<EnrichmentCandidate>
    /** The movie with [tmdbId] is linked to TMDB through [method]; its backoff is cleared. */
    fun markMatched(tmdbId: Int, method: MovieMatchMethod)
    /** No TMDB movie matched with confidence: the movie leaves the queue. */
    fun markUnmatched(dbId: Long)
    /** A TMDB error: the movie is retried later, waiting longer after each failure. */
    fun markEnrichmentFailed(dbId: Long)
    /** Filmow ids the catalog can resolve without the Filmow detail page (TMDB id or IMDb link). */
    fun getResolvableFilmowIds(): Set<String>
    fun findDbIdsByFilmowIds(filmowIds: Collection<String>): List<Long>
    fun getLocalMovieDetail(tmdbId: Int): MovieDetail?
    fun getLocalMovieDetailByDbId(dbId: Long): MovieDetail?
    fun findByTitle(title: String): Movie?
    /** Upserts movies by tmdbId and returns them with their DB-assigned [Movie.id]. */
    fun cacheMovieListWithIds(movies: List<Movie>): List<Movie>
    fun saveFilmowPartials(details: Map<String, FilmowMoviePartial>)
}

data class CachedData<T>(
    val data: T,
    val isStale: Boolean
)

data class EnrichmentCandidate(
    val dbId: Long,
    val tmdbId: Int?,
    val title: String,
    val originalTitle: String?,
    val localTitle: String?,
    val year: Int?,
    val filmowId: String?,
    val imdbUrl: String?,
    val director: String?,
    val needsResolution: Boolean,
    val needsEnrichment: Boolean = true,
    val unmatched: Boolean = false
)
