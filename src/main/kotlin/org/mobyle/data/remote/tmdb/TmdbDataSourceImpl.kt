package org.mobyle.data.remote.tmdb

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import org.mobyle.data.remote.tmdb.model.*

class TmdbDataSourceImpl(
    private val httpClient: HttpClient
) : TmdbDataSource {

    override suspend fun getTrendingMovies(page: Int): TmdbMovieListResponse {
        return httpClient.get("trending/movie/week") {
            parameter("page", page)
        }.body()
    }

    override suspend fun getMovieDetail(movieId: Int): TmdbMovieDetailResponse {
        return httpClient.get("movie/$movieId") {
            parameter("append_to_response", "credits,videos,watch/providers,similar,reviews")
        }.body()
    }

    override suspend fun findByImdbId(imdbId: String): TmdbFindResponse {
        return httpClient.get("find/$imdbId") {
            parameter("external_source", "imdb_id")
        }.body()
    }

    override suspend fun searchMovies(query: String, page: Int, year: Int?, language: String?): TmdbMovieListResponse {
        return httpClient.get("search/movie") {
            parameter("query", query)
            parameter("page", page)
            if (year != null) parameter("year", year)
            if (language != null) parameter("language", language)
        }.body()
    }

    override suspend fun discoverMovies(
        page: Int,
        year: Int?,
        releaseDateGte: String?,
        releaseDateLte: String?,
        sortBy: String?,
        genres: String?,
        language: String?,
        country: String?,
        voteCountGte: Int?
    ): TmdbMovieListResponse {
        return httpClient.get("discover/movie") {
            parameter("page", page)
            year?.let { parameter("primary_release_year", it) }
            releaseDateGte?.let { parameter("primary_release_date.gte", it) }
            releaseDateLte?.let { parameter("primary_release_date.lte", it) }
            sortBy?.let { parameter("sort_by", it) }
            genres?.let { parameter("with_genres", it) }
            language?.let { parameter("with_original_language", it) }
            country?.let { parameter("with_origin_country", it) }
            voteCountGte?.let { parameter("vote_count.gte", it) }
        }.body()
    }

    override suspend fun getGenres(): TmdbGenreListResponse {
        return httpClient.get("genre/movie/list").body()
    }

    override suspend fun getCountries(): List<TmdbCountry> {
        return httpClient.get("configuration/countries").body()
    }

    override suspend fun getLanguages(): List<TmdbLanguage> {
        return httpClient.get("configuration/languages").body()
    }

    override suspend fun getMovieReviews(movieId: Int, page: Int): TmdbReviewListResponse {
        return httpClient.get("movie/$movieId/reviews") {
            parameter("page", page)
        }.body()
    }
}
