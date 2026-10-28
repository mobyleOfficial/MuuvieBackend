package org.mobyle.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import org.mobyle.domain.model.vocabulary.Rating
import org.mobyle.domain.model.vocabulary.WatchStatus

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
    // Kept for the catalog cache and the maratona criteria; the TMDB score is not shown (D3, phase 13).
    @Transient val voteAverage: Double = 0.0,
    // Stars of a Filmow profile, read by the import only.
    @Transient val userRating: Double? = null,
    val releaseDate: String? = null,
    val filmowId: String? = null,
    val watchedAt: String? = null,
    // The viewer's state in the Catálogo lists (B14.2); absent when logged out.
    val me: MovieListState? = null
)

/** Já assisti or Quero ver, and the rating label, of the viewer for a movie in a list. */
@Serializable
data class MovieListState(
    val status: WatchStatus? = null,
    val rating: Rating? = null
)
