package org.mobyle.domain.model

/** The genres and TMDB keywords saved for a catalog movie, used to score similarity. */
data class MovieTraits(
    val genres: Set<String> = emptySet(),
    val keywords: Set<String> = emptySet()
)
