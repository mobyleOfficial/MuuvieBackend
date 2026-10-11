package org.mobyle.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class Locadora(
    val id: Int,
    val name: String,
    val description: String? = null,
    val shelvesCount: Int,
    val totalMoviesCount: Int,
    val shelves: List<MovieShelf>,
    val date: String
)
