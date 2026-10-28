package org.mobyle.data.repository

import org.mobyle.data.local.shelves.ShelfDataSource
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
import org.mobyle.domain.repository.ShelfRepository

class ShelfRepositoryImpl(private val shelfDataSource: ShelfDataSource) : ShelfRepository {
    override fun userExists(userId: String) = shelfDataSource.userExists(userId)

    override fun areMutuals(userId: String, otherUserId: String) = shelfDataSource.areMutuals(userId, otherUserId)

    override fun ensureSystemShelves(userId: String) = shelfDataSource.ensureSystemShelves(userId)

    override fun getShelf(shelfId: Long): Shelf? = shelfDataSource.getShelf(shelfId)

    override fun getUserShelves(
        ownerId: String,
        visibilities: Set<Visibility>,
        page: PageRequest,
        excludeHiddenByLocadoras: Boolean
    ): Page<Shelf> = shelfDataSource.getUserShelves(ownerId, visibilities, page, excludeHiddenByLocadoras)

    override fun searchShelves(ownerId: String, query: String, page: PageRequest): Page<Shelf> =
        shelfDataSource.searchShelves(ownerId, query, page)

    override fun createShelf(ownerId: String, draft: ShelfDraft): Shelf = shelfDataSource.createShelf(ownerId, draft)

    override fun updateShelf(shelfId: Long, draft: ShelfDraft): Shelf = shelfDataSource.updateShelf(shelfId, draft)

    override fun deleteShelf(shelfId: Long) = shelfDataSource.deleteShelf(shelfId)

    override fun getShelfMovies(shelf: Shelf, filter: ShelfMovieFilter, page: PageRequest): Page<ShelfMovie> =
        shelfDataSource.getShelfMovies(shelf, filter, page)

    override fun resolveMovieId(movieId: Long?, tmdbId: Int?, title: String?): Long? =
        shelfDataSource.resolveMovieId(movieId, tmdbId, title)

    override fun findMovieIdByTmdbId(tmdbId: Int): Long? = shelfDataSource.findMovieIdByTmdbId(tmdbId)

    override fun shelfIdsWithMovie(ownerId: String, movieId: Long): List<Long> =
        shelfDataSource.shelfIdsWithMovie(ownerId, movieId)

    override fun publicShelvesWithMovie(movieId: Long, page: PageRequest): Page<PublicShelf> =
        shelfDataSource.publicShelvesWithMovie(movieId, page)

    override fun addMovie(shelf: Shelf, movieId: Long) = shelfDataSource.addMovie(shelf, movieId)

    override fun removeMovie(shelf: Shelf, movieId: Long) = shelfDataSource.removeMovie(shelf, movieId)

    override fun transferMovies(from: Shelf, to: Shelf, mode: ShelfTransferMode, movieIds: List<Long>) =
        shelfDataSource.transferMovies(from, to, mode, movieIds)

    override fun sortShelf(shelfId: Long, sortMode: ShelfSort, applyOnce: Boolean) =
        shelfDataSource.sortShelf(shelfId, sortMode, applyOnce)

    override fun reorderMovies(shelfId: Long, movieIds: List<Long>) = shelfDataSource.reorderMovies(shelfId, movieIds)

    override fun reorderShelves(ownerId: String, shelfIds: List<Long>, pinnedIds: List<Long>?) =
        shelfDataSource.reorderShelves(ownerId, shelfIds, pinnedIds)

    override fun isSaved(shelfId: Long, userId: String): Boolean = shelfDataSource.isSaved(shelfId, userId)

    override fun saveShelf(shelfId: Long, userId: String) = shelfDataSource.saveShelf(shelfId, userId)

    override fun unsaveShelf(shelfId: Long, userId: String) = shelfDataSource.unsaveShelf(shelfId, userId)

    override fun savedShelves(userId: String, page: PageRequest): Page<Shelf> =
        shelfDataSource.savedShelves(userId, page)

    override fun recordVisit(shelfId: Long, userId: String) = shelfDataSource.recordVisit(shelfId, userId)
}
