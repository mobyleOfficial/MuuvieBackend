package org.mobyle.domain.repository

import org.mobyle.domain.model.*
import org.mobyle.model.MovieListing
import org.mobyle.model.MovieListListing
import org.mobyle.model.MovieReviewListing

interface MoviesRepository {
    suspend fun getTrendingMovies(page: Int): MovieListing
    suspend fun getMovieDetail(movieId: Int, userId: String? = null): MovieDetail
    suspend fun lookupMovieDetail(id: Long?, tmdbId: Int?, filmowId: String?, userId: String? = null): MovieDetail?
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
    suspend fun getMovieReviews(page: Int, userId: String?, movieId: Int?): MovieReviewListing
    suspend fun getUserFavoriteMovies(userId: String, page: Int): MovieListing
    suspend fun getUserWatchList(userId: String, page: Int): MovieListing
    suspend fun getUserWatchedMovies(userId: String, page: Int): MovieListing
    suspend fun getMovieLists(page: Int, userId: String?): MovieListListing
    suspend fun getUserMovieLists(page: Int): MovieListListing
    suspend fun getMovieListDetail(listId: Int, page: Int): MovieListDetail
    suspend fun getFeaturedLists(page: Int): MovieListListing
    suspend fun getRecentMovies(userId: String, limit: Int = 10): MovieListing
    suspend fun likeReview(userId: String, reviewId: String)
    suspend fun unlikeReview(userId: String, reviewId: String)
    suspend fun likeMovie(userId: String, movieId: Long)
    suspend fun unlikeMovie(userId: String, movieId: Long)
    suspend fun getPersonDetail(personId: Long): PersonDetail?
}
