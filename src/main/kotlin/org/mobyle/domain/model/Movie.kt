package org.mobyle.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class Movie(
    val id: Long = 0L,
    val tmdbId: Int? = null,
    val title: String,
    val localTitle: String? = null,
    val originalTitle: String? = null,
    val overview: String = "",
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val voteAverage: Double = 0.0,
    val userRating: Double? = null,
    val releaseDate: String? = null,
    val filmowId: String? = null,
    val watchedAt: String? = null
)
