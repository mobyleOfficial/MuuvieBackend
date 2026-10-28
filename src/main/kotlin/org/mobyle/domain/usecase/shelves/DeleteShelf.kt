package org.mobyle.domain.usecase.shelves

import org.mobyle.domain.model.ForbiddenException
import org.mobyle.domain.repository.ShelfRepository
import org.mobyle.domain.usecase.feed.ActivityPublisher

/** Deletes a shelf. Its movies stay in the user's history; system shelves cannot be deleted. */
class DeleteShelf(
    private val repository: ShelfRepository,
    private val access: ShelfAccess,
    private val activities: ActivityPublisher
) {
    operator fun invoke(userId: String, shelfId: Long) {
        val shelf = access.ownedShelf(userId, shelfId)
        if (shelf.systemType != null) {
            throw ForbiddenException("system_shelf_delete", "This shelf cannot be deleted")
        }
        repository.deleteShelf(shelfId)
        activities.shelfDeleted(userId, shelfId)
    }
}
