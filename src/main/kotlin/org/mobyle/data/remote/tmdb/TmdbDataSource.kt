package org.mobyle.data.remote.tmdb

import org.mobyle.data.remote.tmdb.model.*

interface TmdbDataSource {
    suspend fun getTrendingMovies(page: Int): TmdbMovieListResponse
    suspend fun getMovieDetail(movieId: Int): TmdbMovieDetailResponse
    suspend fun findByImdbId(imdbId: String): TmdbFindResponse
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
    suspend fun getGenres(): TmdbGenreListResponse
    suspend fun getCountries(): List<TmdbCountry>
    suspend fun getLanguages(): List<TmdbLanguage>
    suspend fun getMovieReviews(movieId: Int, page: Int): TmdbReviewListResponse
}
