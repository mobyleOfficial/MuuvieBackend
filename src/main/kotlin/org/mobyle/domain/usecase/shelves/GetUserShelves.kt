package org.mobyle.domain.usecase.shelves

import org.mobyle.domain.model.NotFoundException
import org.mobyle.domain.model.Page
import org.mobyle.domain.model.PageRequest
import org.mobyle.domain.model.Shelf
import org.mobyle.domain.policy.ViewerRelation
import org.mobyle.domain.policy.VisibilityPolicy
import org.mobyle.domain.repository.ShelfRepository

/**
 * Shelves of [ownerId] that [viewerId] can see, pinned first, then in profile order.
 * Others do not see the shelves a locadora hides from the profile. The owner still does, to manage them,
 * except on the profile itself ([profile]).
 */
class GetUserShelves(private val repository: ShelfRepository, private val access: ShelfAccess) {
    operator fun invoke(viewerId: String?, ownerId: String, page: PageRequest, profile: Boolean = false): Page<Shelf> {
        if (!repository.userExists(ownerId)) throw NotFoundException("User not found")
        repository.ensureSystemShelves(ownerId)
        val relation = access.relation(viewerId, ownerId)
        val visibilities = VisibilityPolicy.visibleTo(relation)
        val shelves = repository.getUserShelves(ownerId, visibilities, page, profile || relation != ViewerRelation.OWNER)
        return shelves.copy(results = shelves.results.map { it.copy(self = it.ownerId == viewerId) })
    }
}
