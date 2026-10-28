package org.mobyle.domain.usecase.shelves

import org.mobyle.domain.model.ValidationException
import org.mobyle.domain.model.vocabulary.ShelfSort
import org.mobyle.domain.repository.ShelfRepository

/**
 * Orders the movies of a shelf (F1.6): either a sort mode (optionally applied only once,
 * which saves the result as the manual order) or an explicit manual order.
 */
class SortShelf(private val repository: ShelfRepository, private val access: ShelfAccess) {
    operator fun invoke(userId: String, shelfId: Long, sortMode: String?, applyOnce: Boolean, movieIds: List<Long>?) {
        access.ownedShelf(userId, shelfId)
        when {
            movieIds != null -> {
                repository.sortShelf(shelfId, ShelfSort.MANUAL, applyOnce = false)
                repository.reorderMovies(shelfId, movieIds.distinct())
            }
            sortMode != null -> {
                val sort = ShelfSort.fromWireValue(sortMode)
                    ?: throw ValidationException("sortMode", "Unknown sort mode")
                repository.sortShelf(shelfId, sort, applyOnce)
            }
            else -> throw ValidationException("sortMode", "sortMode or movieIds is required")
        }
    }
}
