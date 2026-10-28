package org.mobyle.domain.usecase.locadoras

import org.mobyle.domain.model.Locadora
import org.mobyle.domain.repository.LocadoraRepository
import org.mobyle.domain.usecase.feed.ActivityPublisher

class CreateLocadora(private val repository: LocadoraRepository, private val activities: ActivityPublisher) {
    operator fun invoke(
        userId: String,
        name: String,
        description: String?,
        visibility: String?,
        hideShelvesInProfile: Boolean,
        hideShelvesInFilters: Boolean
    ): Locadora {
        val draft = locadoraDraftOf(name, description, visibility, hideShelvesInProfile, hideShelvesInFilters)
        val locadora = repository.createLocadora(userId, draft)
        activities.locadoraCreated(locadora)
        return locadora.copy(self = true)
    }
}
