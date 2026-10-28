package org.mobyle.domain.usecase.shelves

import org.mobyle.domain.model.ForbiddenException
import org.mobyle.domain.model.NotFoundException
import org.mobyle.domain.model.Shelf
import org.mobyle.domain.policy.ViewerRelation
import org.mobyle.domain.policy.VisibilityPolicy
import org.mobyle.domain.repository.ShelfRepository

/** Loads shelves through the visibility rules shared by every shelf use case. */
class ShelfAccess(private val repository: ShelfRepository) {

    fun relation(viewerId: String?, ownerId: String): ViewerRelation = when {
        viewerId == null -> ViewerRelation.OTHER
        viewerId == ownerId -> ViewerRelation.OWNER
        repository.areMutuals(viewerId, ownerId) -> ViewerRelation.MUTUAL
        else -> ViewerRelation.OTHER
    }

    /** The shelf as [viewerId] sees it. A shelf the viewer cannot see does not exist for them. */
    fun visibleShelf(viewerId: String?, shelfId: Long): Shelf {
        val shelf = repository.getShelf(shelfId) ?: throw NotFoundException("Shelf not found")
        if (!VisibilityPolicy.canView(relation(viewerId, shelf.ownerId), shelf.visibility)) {
            throw NotFoundException("Shelf not found")
        }
        return shelf.copy(self = shelf.ownerId == viewerId)
    }

    /** A shelf [userId] may change. */
    fun ownedShelf(userId: String, shelfId: Long): Shelf {
        val shelf = visibleShelf(userId, shelfId)
        if (!shelf.self) throw ForbiddenException("not_shelf_owner", "Only the owner can change this shelf")
        return shelf
    }
}
