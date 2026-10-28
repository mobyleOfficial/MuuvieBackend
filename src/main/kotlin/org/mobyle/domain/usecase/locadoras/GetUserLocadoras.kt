package org.mobyle.domain.usecase.locadoras

import org.mobyle.domain.model.Locadora
import org.mobyle.domain.model.NotFoundException
import org.mobyle.domain.model.Page
import org.mobyle.domain.model.PageRequest
import org.mobyle.domain.policy.VisibilityPolicy
import org.mobyle.domain.repository.LocadoraRepository
import org.mobyle.domain.repository.ShelfRepository
import org.mobyle.domain.usecase.shelves.ShelfAccess

/** Locadoras of [ownerId] that [viewerId] can see, pinned first, then in profile order (F4.5). */
class GetUserLocadoras(
    private val repository: LocadoraRepository,
    private val shelfRepository: ShelfRepository,
    private val shelves: ShelfAccess
) {
    operator fun invoke(viewerId: String?, ownerId: String, page: PageRequest): Page<Locadora> {
        if (!shelfRepository.userExists(ownerId)) throw NotFoundException("User not found")
        val visibilities = VisibilityPolicy.visibleTo(shelves.relation(viewerId, ownerId))
        val locadoras = repository.getUserLocadoras(ownerId, visibilities, page)
        return locadoras.copy(results = locadoras.results.map { it.copy(self = it.ownerId == viewerId) })
    }
}
