package org.mobyle.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class MovieDetail(
    val id: Long = 0L,
    val tmdbId: Int? = null,
    val title: String,
    val localTitle: String? = null,
    val originalTitle: String? = null,
    val overview: String,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val voteAverage: Double = 0.0,
    val releaseDate: String? = null,
    val tagline: String? = null,
    val runtime: Int? = null,
    val genres: List<String> = emptyList(),
    val director: Person? = null,
    val writers: List<Person> = emptyList(),
    val cast: List<CastMember> = emptyList(),
    val trailerKey: String? = null,
    val watchProviders: List<WatchProvider> = emptyList(),
    val similarMovies: List<Movie> = emptyList(),
    val popularReviews: List<MovieReview> = emptyList(),
    val reviewCount: Int = 0,
    val listCount: Int = 0,
    val likeCount: Int = 0,
    val likedByMe: Boolean = false
)
