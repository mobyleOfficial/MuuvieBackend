package org.mobyle.domain.model

import kotlinx.serialization.Serializable
import org.mobyle.domain.model.vocabulary.HighlightType

/**
 * A section of the profile's Destaques tab (F5.4). The configuration ([movieIds], [shelfId],
 * [locadoraId], [soundtrackUrl]) comes with what it points to, resolved for the viewer:
 * a shelf or locadora the viewer cannot see is left out, and so is the section.
 */
@Serializable
data class ProfileHighlight(
    val type: HighlightType,
    val position: Int,
    val enabled: Boolean,
    val movieIds: List<Long> = emptyList(),
    val shelfId: Long? = null,
    val locadoraId: Long? = null,
    val soundtrackUrl: String? = null,
    val movies: List<MovieCard> = emptyList(),
    val shelf: Shelf? = null,
    val locadora: Locadora? = null
)

/** A highlight as stored, already validated. */
data class HighlightDraft(
    val type: HighlightType,
    val enabled: Boolean,
    val movieIds: List<Long> = emptyList(),
    val shelfId: Long? = null,
    val locadoraId: Long? = null,
    val soundtrackUrl: String? = null
)

/** A movie as lists of posters need it (highlights, news). */
@Serializable
data class MovieCard(
    val movieId: Long,
    val tmdbId: Int?,
    val title: String,
    val posterPath: String?,
    val year: Int?
)
