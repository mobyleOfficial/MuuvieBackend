package org.mobyle.domain.usecase.shelves

import org.mobyle.domain.model.Shelf
import org.mobyle.domain.repository.ShelfRepository

/**
 * A shelf as the viewer sees it. A logged-in viewer who is not the owner counts as a visit
 * for the ranking of /discover/shelves and learns whether they saved it (B14.4).
 */
class GetShelf(private val access: ShelfAccess, private val repository: ShelfRepository) {
    operator fun invoke(viewerId: String?, shelfId: Long): Shelf {
        val shelf = access.visibleShelf(viewerId, shelfId)
        if (viewerId == null || shelf.self) return shelf
        repository.recordVisit(shelfId, viewerId)
        return shelf.copy(saved = repository.isSaved(shelfId, viewerId))
    }
}
