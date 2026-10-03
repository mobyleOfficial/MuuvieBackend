package org.mobyle.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class MovieDetail(
    val id: Long = 0L,
    val tmdbId: Int? = null,
    val title: String,
    val originalTitle: String? = null,
    val overview: String,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val voteAverage: Double = 0.0,
    val releaseDate: String? = null,
    val tagline: String? = null,
    val runtime: Int? = null,
    val genres: List<String> = emptyList(),
    val director: String? = null,
    val cast: List<String> = emptyList(),
    val watchProviders: List<WatchProvider> = emptyList(),
    val similarMovies: List<Movie> = emptyList(),
    val popularReviews: List<MovieReview> = emptyList(),
    val reviewCount: Int = 0,
    val listCount: Int = 0,
    val likeCount: Int = 0
)
