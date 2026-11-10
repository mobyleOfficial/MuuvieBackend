package org.mobyle.domain.model

import kotlinx.serialization.Serializable

/**
 * The movies TMDB considers similar to one movie, as `GET /movies/{id}/similar` answers them.
 * [results] is sorted by [SimilarityAffinity.score], most similar first, and [affinities] holds one
 * entry per result in the same order. Kept as a separate list so older clients reading only
 * [results] are not affected.
 */
@Serializable
data class SimilarMovies(
    val movieId: Long,
    val results: List<Movie>,
    val affinities: List<SimilarityAffinity> = emptyList()
)

/**
 * How close one similar movie is to the movie that was asked for: [score] from 0 to 1, and the
 * genres and keywords both share, which explain the score. [genres] are all the genres of the
 * similar movie itself, so the app can filter the planets of a system by genre; empty while the
 * movie has not been enriched.
 */
@Serializable
data class SimilarityAffinity(
    val movieId: Long,
    val score: Double,
    val sharedGenres: List<String> = emptyList(),
    val sharedKeywords: List<String> = emptyList(),
    val genres: List<String> = emptyList()
)
