package org.mobyle.domain.repository

import org.mobyle.domain.model.*
import org.mobyle.model.MovieListing

interface MoviesRepository {
    suspend fun getTrendingMovies(page: Int): MovieListing
    suspend fun getMovieDetail(movieId: Int): MovieDetail
    suspend fun lookupMovieDetail(id: Long?, tmdbId: Int?, filmowId: String?): MovieDetail?
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
