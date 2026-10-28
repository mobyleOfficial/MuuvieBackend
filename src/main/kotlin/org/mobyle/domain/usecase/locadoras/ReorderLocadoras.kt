package org.mobyle.domain.usecase.locadoras

import org.mobyle.domain.model.ValidationException
import org.mobyle.domain.repository.LocadoraRepository

/** Orders the user's locadoras on the profile and sets the pinned ones. */
class ReorderLocadoras(private val repository: LocadoraRepository, private val access: LocadoraAccess) {
    operator fun invoke(userId: String, locadoraIds: List<Long>, pinnedIds: List<Long>?) {
        if (locadoraIds.isEmpty() && pinnedIds == null) {
            throw ValidationException("locadoraIds", "locadoraIds or pinnedIds is required")
        }
        (locadoraIds + pinnedIds.orEmpty()).distinct().forEach { access.ownedLocadora(userId, it) }
        repository.reorderLocadoras(userId, locadoraIds.distinct(), pinnedIds?.distinct())
    }
}
