package org.mobyle.data.local.movies

import org.mobyle.domain.model.FilmowMoviePartial
import org.mobyle.domain.model.Movie
import org.mobyle.domain.model.MovieDetail
import org.mobyle.domain.model.WatchProvider
import org.mobyle.data.remote.tmdb.model.TmdbCredits

interface MovieCatalogDataSource {
    fun findById(id: Long): Movie?
    fun findByTmdbId(tmdbId: Int): Movie?
    fun findByFilmowId(filmowId: String): Movie?
    fun findByLetterboxdId(letterboxdId: String): Movie?
    fun getDbIdByFilmowId(filmowId: String): Long?
    fun upsertMovie(movie: Movie, filmowId: String? = null, letterboxdId: String? = null): Long
    fun enrichMovie(tmdbId: Int, detail: MovieDetail, credits: TmdbCredits?)
    fun saveSimilarMovies(tmdbId: Int, similars: List<Movie>)
    fun saveWatchProviders(tmdbId: Int, providers: List<WatchProvider>)
    fun getSimilarMovies(tmdbId: Int): CachedData<List<Movie>>
    fun getWatchProviders(tmdbId: Int): CachedData<List<WatchProvider>>
    fun resolveScrapedMovie(oldDbId: Long, realTmdbId: Int, filmowId: String?)
    fun getMoviesNeedingEnrichment(limit: Int = 50): List<EnrichmentCandidate>
    fun getLocalMovieDetail(tmdbId: Int): MovieDetail?
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
    val year: Int?,
    val filmowId: String?,
    val needsResolution: Boolean
)
