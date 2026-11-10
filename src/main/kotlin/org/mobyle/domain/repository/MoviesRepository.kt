package org.mobyle.domain.repository

import org.mobyle.domain.model.*
import org.mobyle.model.MovieListing

interface MoviesRepository {
    suspend fun getTrendingMovies(page: Int): MovieListing
    suspend fun getMovieDetail(movieId: Int): MovieDetail
    suspend fun lookupMovieDetail(id: Long?, tmdbId: Int?, filmowId: String?): MovieDetail?
    /**
     * The similar movies of the catalog movie [movieId], from the `movie_similars` cache or TMDB.
     * Null when no such movie exists; empty when it has no TMDB id or TMDB cannot be reached.
     */
    suspend fun getSimilarMovies(movieId: Long): List<Movie>?
    /** The saved genres and keywords of each catalog movie in [movieIds]; movies without any are left out. */
    suspend fun getMovieTraits(movieIds: Collection<Long>): Map<Long, MovieTraits>
    /**
     * Puts the catalog movies of [movieIds] still waiting for enrichment ahead of the background
     * loop, so their genres and keywords come sooner. Returns at once; the work runs apart.
     */
    fun enrichAhead(movieIds: Collection<Long>)
    suspend fun searchMovies(query: String, page: Int): MovieListing
    suspend fun discoverMovies(
        page: Int,
        year: Int? = null,
        releaseDateGte: String? = null,
        releaseDateLte: String? = null,
        sortBy: String? = null,
        genres: String? = null,
        language: String? = null,
        country: String? = null,
        voteCountGte: Int? = null
    ): MovieListing
    suspend fun getGenres(): List<Genre>
    suspend fun getCountries(): List<Country>
    suspend fun getLanguages(): List<Language>
    suspend fun getPersonDetail(personId: Long): PersonDetail?
    /** The movie detail lists the cast straight from TMDB, so the app only has the TMDB id (F5.7). */
    suspend fun getPersonDetailByTmdbId(tmdbId: Int): PersonDetail?
}
