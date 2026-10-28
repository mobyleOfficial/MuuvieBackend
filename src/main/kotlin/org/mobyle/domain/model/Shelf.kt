package org.mobyle.domain.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import org.mobyle.domain.model.vocabulary.Rating
import org.mobyle.domain.model.vocabulary.ShelfSort
import org.mobyle.domain.model.vocabulary.SystemShelfType
import org.mobyle.domain.model.vocabulary.Visibility

/** A shelf of the v1 API. Shelves only hold membership; the movie state lives in user_movies. */
@Serializable
data class Shelf(
    val id: Long,
    val ownerId: String,
    val name: String,
    val description: String?,
    val color: String?,
    val visibility: Visibility,
    val systemType: SystemShelfType?,
    val sortMode: ShelfSort,
    val position: Int,
    val pinned: Boolean,
    val coverUrl: String?,
    val totalMovies: Int,
    val totalWatched: Int,
    val posterPaths: List<String>,
    val createdAt: Instant,
    val updatedAt: Instant,
    val self: Boolean = false,
    // B14.4: who made it, whether the viewer saved it and how many people did.
    val owner: UserSummary? = null,
    val saved: Boolean = false,
    val saves: Int = 0
)

/** A public custom shelf that holds a movie, with its owner (B13.2). */
@Serializable
data class PublicShelf(val shelf: Shelf, val owner: UserSummary)

/** A movie inside a shelf, with the owner's state for it. */
@Serializable
data class ShelfMovie(
    val movieId: Long,
    val tmdbId: Int?,
    val title: String,
    val posterPath: String?,
    val year: Int?,
    val director: String?,
    val runtime: Int?,
    val position: Int,
    val addedAt: Instant,
    val watched: Boolean,
    val rating: Rating?
)

/** Editable fields of a shelf, already validated. */
data class ShelfDraft(
    val name: String,
    val description: String?,
    val color: String?,
    val visibility: Visibility
)

/** Filters of GET /shelves/{id}/movies (F1.8). Null means "any". */
data class ShelfMovieFilter(
    val watched: Boolean? = null,
    val genreTmdbId: Int? = null,
    val decade: Int? = null,
    val rating: Rating? = null
)

enum class ShelfTransferMode { MOVE, COPY }
