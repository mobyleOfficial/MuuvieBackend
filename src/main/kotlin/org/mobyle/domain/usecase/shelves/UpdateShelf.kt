package org.mobyle.domain.usecase.shelves

import org.mobyle.domain.model.ForbiddenException
import org.mobyle.domain.model.Shelf
import org.mobyle.domain.repository.ShelfRepository
import org.mobyle.domain.usecase.feed.ActivityPublisher

/** Edits a shelf. System shelves keep their name; everything else is editable (F1.2). */
class UpdateShelf(
    private val repository: ShelfRepository,
    private val access: ShelfAccess,
    private val activities: ActivityPublisher
) {
    operator fun invoke(
        userId: String,
        shelfId: Long,
        name: String,
        description: String?,
        color: String?,
        visibility: String?
    ): Shelf {
        val shelf = access.ownedShelf(userId, shelfId)
        val draft = shelfDraftOf(name, description, color, visibility)
        if (shelf.systemType != null && draft.name != shelf.name) {
            throw ForbiddenException("system_shelf_rename", "This shelf cannot be renamed")
        }
        val updated = repository.updateShelf(shelfId, draft)
        activities.shelfChanged(updated)
        return updated.copy(self = true)
    }
}
