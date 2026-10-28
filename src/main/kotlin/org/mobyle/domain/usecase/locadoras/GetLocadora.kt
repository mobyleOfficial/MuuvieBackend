package org.mobyle.domain.usecase.locadoras

import org.mobyle.domain.model.Locadora

class GetLocadora(private val access: LocadoraAccess) {
    operator fun invoke(viewerId: String?, locadoraId: Long): Locadora = access.visibleLocadora(viewerId, locadoraId)
}
