package org.mobyle.domain.usecase.locadoras

import org.mobyle.domain.model.LocadoraShelf
import org.mobyle.domain.model.Page
import org.mobyle.domain.model.PageRequest
import org.mobyle.domain.policy.VisibilityPolicy
import org.mobyle.domain.repository.LocadoraRepository
import org.mobyle.domain.usecase.shelves.ShelfAccess

private const val MOVIES_PER_SHELF = 10

/**
 * Shelves of a locadora, in its order, each with its first movies (F4.4).
 * A shelf the viewer cannot see stays hidden even inside a public locadora.
 */
class GetLocadoraShelves(
    private val repository: LocadoraRepository,
    private val access: LocadoraAccess,
    private val shelves: ShelfAccess
) {
    operator fun invoke(viewerId: String?, locadoraId: Long, page: PageRequest): Page<LocadoraShelf> {
        val locadora = access.visibleLocadora(viewerId, locadoraId)
        val visibilities = VisibilityPolicy.visibleTo(shelves.relation(viewerId, locadora.ownerId))
        val result = repository.getLocadoraShelves(locadoraId, visibilities, page, MOVIES_PER_SHELF)
        return result.copy(
            results = result.results.map { it.copy(shelf = it.shelf.copy(self = it.shelf.ownerId == viewerId)) }
        )
    }
}
