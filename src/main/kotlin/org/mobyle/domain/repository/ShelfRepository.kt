package org.mobyle.domain.repository

import org.mobyle.domain.model.Page
import org.mobyle.domain.model.PageRequest
import org.mobyle.domain.model.PublicShelf
import org.mobyle.domain.model.Shelf
import org.mobyle.domain.model.ShelfDraft
import org.mobyle.domain.model.ShelfMovie
import org.mobyle.domain.model.ShelfMovieFilter
import org.mobyle.domain.model.ShelfTransferMode
import org.mobyle.domain.model.vocabulary.ShelfSort
import org.mobyle.domain.model.vocabulary.Visibility

/**
 * Shelves and their membership. Users are identified by their external id.
 * Adding to or removing from a system shelf also updates the user's movie status
 * (Já assisti and Quero ver exclude each other).
 */
interface ShelfRepository {
    fun userExists(userId: String): Boolean
    fun areMutuals(userId: String, otherUserId: String): Boolean
    fun ensureSystemShelves(userId: String)

    fun getShelf(shelfId: Long): Shelf?
    /** With [excludeHiddenByLocadoras], leaves out the shelves of locadoras that hide them from the profile (F4.1). */
    fun getUserShelves(
        ownerId: String,
        visibilities: Set<Visibility>,
        page: PageRequest,
        excludeHiddenByLocadoras: Boolean = false
    ): Page<Shelf>
    fun searchShelves(ownerId: String, query: String, page: PageRequest): Page<Shelf>
    fun createShelf(ownerId: String, draft: ShelfDraft): Shelf
    fun updateShelf(shelfId: Long, draft: ShelfDraft): Shelf
    fun deleteShelf(shelfId: Long)

    fun getShelfMovies(shelf: Shelf, filter: ShelfMovieFilter, page: PageRequest): Page<ShelfMovie>
    /** Database id of the movie, creating a stub (enriched later) when only the TMDB id is known. */
    fun resolveMovieId(movieId: Long?, tmdbId: Int?, title: String?): Long?
    fun findMovieIdByTmdbId(tmdbId: Int): Long?
    /** Ids of the owner's shelves that contain the movie. */
    fun shelfIdsWithMovie(ownerId: String, movieId: Long): List<Long>
    /** Public custom shelves of anyone that hold the movie, last updated first (D5). */
    fun publicShelvesWithMovie(movieId: Long, page: PageRequest): Page<PublicShelf>
    fun addMovie(shelf: Shelf, movieId: Long)
    fun removeMovie(shelf: Shelf, movieId: Long)
    fun transferMovies(from: Shelf, to: Shelf, mode: ShelfTransferMode, movieIds: List<Long>)

    /** Saves the sort mode; with [applyOnce], writes the resulting order as manual positions instead. */
    fun sortShelf(shelfId: Long, sortMode: ShelfSort, applyOnce: Boolean)
    /** Manual order: [movieIds] first, in this order; the rest keep their relative order after them. */
    fun reorderMovies(shelfId: Long, movieIds: List<Long>)
    /** Profile order: [shelfIds] first, in this order. When [pinnedIds] is given, it replaces the pinned set. */
    fun reorderShelves(ownerId: String, shelfIds: List<Long>, pinnedIds: List<Long>?)

    /** Shelves of others that a user saved (B14.4); saving twice keeps one. */
    fun isSaved(shelfId: Long, userId: String): Boolean
    fun saveShelf(shelfId: Long, userId: String)
    fun unsaveShelf(shelfId: Long, userId: String)
    /** The saved shelves [userId] can still see, the last saved first. */
    fun savedShelves(userId: String, page: PageRequest): Page<Shelf>
    /** Keeps the last time [userId] opened the shelf, for the ranking of /discover/shelves. */
    fun recordVisit(shelfId: Long, userId: String)
}
