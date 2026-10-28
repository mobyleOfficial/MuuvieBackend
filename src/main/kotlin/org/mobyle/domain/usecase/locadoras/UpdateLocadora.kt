package org.mobyle.domain.usecase.locadoras

import org.mobyle.domain.model.Locadora
import org.mobyle.domain.repository.LocadoraRepository
import org.mobyle.domain.usecase.feed.ActivityPublisher

class UpdateLocadora(
    private val repository: LocadoraRepository,
    private val access: LocadoraAccess,
    private val activities: ActivityPublisher
) {
    operator fun invoke(
        userId: String,
        locadoraId: Long,
        name: String,
        description: String?,
        visibility: String?,
        hideShelvesInProfile: Boolean,
        hideShelvesInFilters: Boolean
    ): Locadora {
        access.ownedLocadora(userId, locadoraId)
        val draft = locadoraDraftOf(name, description, visibility, hideShelvesInProfile, hideShelvesInFilters)
        val updated = repository.updateLocadora(locadoraId, draft)
        activities.locadoraChanged(updated)
        return updated.copy(self = true)
    }
}
