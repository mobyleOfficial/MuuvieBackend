package org.mobyle.data.remote.tmdb

import org.mobyle.data.remote.tmdb.model.*

interface TmdbDataSource {
    suspend fun getTrendingMovies(page: Int): TmdbMovieListResponse
    suspend fun getMovieDetail(movieId: Int): TmdbMovieDetailResponse
    suspend fun findByImdbId(imdbId: String): TmdbFindResponse
    /** English title, alternative titles and credits of a movie, used to confirm a match. */
    suspend fun getMovieFacts(movieId: Int): TmdbMovieFactsResponse
    suspend fun searchMovies(query: String, page: Int, year: Int? = null, language: String? = null): TmdbMovieListResponse
    suspend fun discoverMovies(
        page: Int,
        year: Int?,
        releaseDateGte: String?,
        releaseDateLte: String?,
        sortBy: String?,
        genres: String?,
        language: String?,
        country: String?,
        voteCountGte: Int?
    ): TmdbMovieListResponse
    /** discover/movie with raw TMDB parameters (B7: suggestions for a challenge's criteria). */
    suspend fun discoverWith(params: Map<String, String>, page: Int): TmdbMovieListResponse
    suspend fun getGenres(): TmdbGenreListResponse
    suspend fun getCountries(): List<TmdbCountry>
    suspend fun getLanguages(): List<TmdbLanguage>
}
