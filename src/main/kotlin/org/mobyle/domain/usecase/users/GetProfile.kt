package org.mobyle.domain.usecase.users

import org.mobyle.domain.model.Profile

/** A profile by id or @handle, as [viewerId] sees it (F5.5). */
class GetProfile(private val access: ProfileAccess) {
    operator fun invoke(viewerId: String?, idOrHandle: String): Profile =
        access.profile(viewerId, access.resolveUserId(idOrHandle))
}
