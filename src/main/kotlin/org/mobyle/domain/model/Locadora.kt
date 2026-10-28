package org.mobyle.domain.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import org.mobyle.domain.model.vocabulary.Visibility

/**
 * A locadora of the v1 API: a group of shelves (B4). The counters and posters only
 * cover the shelves the viewer can see, so a secret shelf never leaks through them.
 */
@Serializable
data class Locadora(
    val id: Long,
    val ownerId: String,
    val ownerUsername: String,
    val ownerAvatarUrl: String?,
    val name: String,
    val description: String?,
    val visibility: Visibility,
    val hideShelvesInProfile: Boolean,
    val hideShelvesInFilters: Boolean,
    val position: Int,
    val pinned: Boolean,
    val coverUrl: String?,
    val totalShelves: Int,
    // Distinct movies across the shelves, and how many of them the owner watched.
    val totalMovies: Int,
    val totalWatched: Int,
    val posterPaths: List<String>,
    val createdAt: Instant,
    val updatedAt: Instant,
    val self: Boolean = false
)

/** A shelf inside a locadora, with the first movies for the horizontal "corridor". */
@Serializable
data class LocadoraShelf(val shelf: Shelf, val movies: List<ShelfMovie>)

/** Editable fields of a locadora, already validated. */
data class LocadoraDraft(
    val name: String,
    val description: String?,
    val visibility: Visibility,
    val hideShelvesInProfile: Boolean,
    val hideShelvesInFilters: Boolean
)
