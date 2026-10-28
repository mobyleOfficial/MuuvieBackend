package org.mobyle.domain.usecase.locadoras

import org.mobyle.domain.repository.LocadoraRepository
import org.mobyle.domain.usecase.feed.ActivityPublisher

/** Deletes a locadora. Its shelves stay on the profile (F4.1). */
class DeleteLocadora(
    private val repository: LocadoraRepository,
    private val access: LocadoraAccess,
    private val activities: ActivityPublisher
) {
    operator fun invoke(userId: String, locadoraId: Long) {
        access.ownedLocadora(userId, locadoraId)
        repository.deleteLocadora(locadoraId)
        activities.locadoraDeleted(userId, locadoraId)
    }
}
