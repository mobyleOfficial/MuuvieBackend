package org.mobyle.domain.usecase.shelves

import org.mobyle.domain.model.Page
import org.mobyle.domain.model.PageRequest
import org.mobyle.domain.model.Shelf
import org.mobyle.domain.model.ValidationException
import org.mobyle.domain.repository.ShelfRepository

/**
 * Saves a shelf of someone else (B14.4). Saving twice keeps one; the shelf must be visible to
 * [userId], and neither their own shelves nor Já assisti / Quero ver can be saved.
 */
class SaveShelf(private val repository: ShelfRepository, private val access: ShelfAccess) {
    operator fun invoke(userId: String, shelfId: Long) {
        val shelf = access.visibleShelf(userId, shelfId)
        if (shelf.self) throw ValidationException("shelfId", "You cannot save your own shelf")
        if (shelf.systemType != null) throw ValidationException("shelfId", "System shelves cannot be saved")
        repository.saveShelf(shelfId, userId)
    }
}

/** Takes a shelf out of the saved ones; a shelf that was not saved stays as it is. */
class UnsaveShelf(private val repository: ShelfRepository, private val access: ShelfAccess) {
    operator fun invoke(userId: String, shelfId: Long) {
        access.visibleShelf(userId, shelfId)
        repository.unsaveShelf(shelfId, userId)
    }
}

/** The "Salvas" list: shelves [userId] saved and can still see, the last saved first. */
class GetSavedShelves(private val repository: ShelfRepository) {
    operator fun invoke(userId: String, page: PageRequest): Page<Shelf> = repository.savedShelves(userId, page)
}
