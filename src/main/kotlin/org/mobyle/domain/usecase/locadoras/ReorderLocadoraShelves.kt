package org.mobyle.domain.usecase.locadoras

import org.mobyle.domain.model.ValidationException
import org.mobyle.domain.repository.LocadoraRepository

/** Orders the shelves inside a locadora (F4.3). Every id must already be in it. */
class ReorderLocadoraShelves(private val repository: LocadoraRepository, private val access: LocadoraAccess) {
    operator fun invoke(userId: String, locadoraId: Long, shelfIds: List<Long>) {
        access.ownedLocadora(userId, locadoraId)
        val ids = locadoraShelfIdsOf(shelfIds)
        if (ids.isEmpty()) throw ValidationException("shelfIds", "shelfIds is required")
        if (!repository.locadoraShelfIds(locadoraId).containsAll(ids)) {
            throw ValidationException("shelfIds", "Every shelf must be in the locadora")
        }
        repository.reorderShelves(locadoraId, ids)
    }
}
