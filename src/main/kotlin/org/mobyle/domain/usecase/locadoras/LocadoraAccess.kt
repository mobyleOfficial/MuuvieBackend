package org.mobyle.domain.usecase.locadoras

import org.mobyle.domain.model.ForbiddenException
import org.mobyle.domain.model.Locadora
import org.mobyle.domain.model.NotFoundException
import org.mobyle.domain.policy.VisibilityPolicy
import org.mobyle.domain.repository.LocadoraRepository
import org.mobyle.domain.usecase.shelves.ShelfAccess

/**
 * Loads locadoras through the visibility rules. A locadora the viewer cannot see does not
 * exist for them; inside a visible one, each shelf keeps its own visibility.
 */
class LocadoraAccess(private val repository: LocadoraRepository, private val shelves: ShelfAccess) {

    fun visibleLocadora(viewerId: String?, locadoraId: Long): Locadora {
        val ownerId = repository.getLocadoraOwnerId(locadoraId) ?: throw NotFoundException("Locadora not found")
        val relation = shelves.relation(viewerId, ownerId)
        val locadora = repository.getLocadora(locadoraId, VisibilityPolicy.visibleTo(relation))
        if (locadora == null || !VisibilityPolicy.canView(relation, locadora.visibility)) {
            throw NotFoundException("Locadora not found")
        }
        return locadora.copy(self = ownerId == viewerId)
    }

    /** A locadora [userId] may change. */
    fun ownedLocadora(userId: String, locadoraId: Long): Locadora {
        val locadora = visibleLocadora(userId, locadoraId)
        if (!locadora.self) throw ForbiddenException("not_locadora_owner", "Only the owner can change this locadora")
        return locadora
    }

    /** Every shelf must belong to [userId]: 404 when the viewer cannot see it, 403 when it is someone else's. */
    fun requireOwnedShelves(userId: String, shelfIds: List<Long>) {
        shelfIds.forEach { shelves.ownedShelf(userId, it) }
    }
}
