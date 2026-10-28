package org.mobyle.domain.usecase.locadoras

import org.mobyle.domain.repository.LocadoraRepository

/** Replaces the shelves of a locadora (F4.2). Only the owner's own shelves can go in. */
class SetLocadoraShelves(private val repository: LocadoraRepository, private val access: LocadoraAccess) {
    operator fun invoke(userId: String, locadoraId: Long, shelfIds: List<Long>) {
        access.ownedLocadora(userId, locadoraId)
        val ids = locadoraShelfIdsOf(shelfIds)
        access.requireOwnedShelves(userId, ids)
        repository.setShelves(locadoraId, ids)
    }
}
