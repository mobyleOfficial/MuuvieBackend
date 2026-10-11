package org.mobyle.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class MovieShelf(
    val id: Int,
    val name: String,
    val date: String,
    val moviesWatchedCount: Int,
    val totalMoviesCount: Int,
    val movies: List<Movie>,
    val currentPage: Int,
    val totalPages: Int,
    val color: String? = null
)
