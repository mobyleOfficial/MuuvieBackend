package org.mobyle.domain.usecase.shelves

import org.mobyle.domain.model.Shelf
import org.mobyle.domain.repository.ShelfRepository
import org.mobyle.domain.usecase.feed.ActivityPublisher

class CreateShelf(private val repository: ShelfRepository, private val activities: ActivityPublisher) {
    operator fun invoke(userId: String, name: String, description: String?, color: String?, visibility: String?): Shelf {
        val draft = shelfDraftOf(name, description, color, visibility)
        val shelf = repository.createShelf(userId, draft)
        activities.shelfCreated(shelf)
        return shelf.copy(self = true)
    }
}
