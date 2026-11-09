package org.mobyle.domain.model

import kotlinx.serialization.Serializable

/**
 * The movies TMDB considers similar to one movie, as `GET /movies/{id}/similar` answers them.
 * An object rather than a bare list so per-movie scores can be added without breaking clients.
 */
@Serializable
data class SimilarMovies(
    val movieId: Long,
    val results: List<Movie>
)
