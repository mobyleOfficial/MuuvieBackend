package org.mobyle.domain.usecase.shelves

import org.mobyle.domain.model.ValidationException
import org.mobyle.domain.repository.ShelfRepository

/** Orders the user's shelves on the profile and sets the pinned ones (F1.7). */
class ReorderShelves(private val repository: ShelfRepository, private val access: ShelfAccess) {
    operator fun invoke(userId: String, shelfIds: List<Long>, pinnedIds: List<Long>?) {
        if (shelfIds.isEmpty() && pinnedIds == null) {
            throw ValidationException("shelfIds", "shelfIds or pinnedIds is required")
        }
        (shelfIds + pinnedIds.orEmpty()).distinct().forEach { access.ownedShelf(userId, it) }
        repository.reorderShelves(userId, shelfIds.distinct(), pinnedIds?.distinct())
    }
}
