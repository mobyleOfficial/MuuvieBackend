package org.mobyle.model

import kotlinx.serialization.Serializable
import org.mobyle.domain.model.MovieShelf

@Serializable
data class MovieShelfListing(
    val totalPages: Int,
    val totalResults: Int,
    val shelves: List<MovieShelf>
)
