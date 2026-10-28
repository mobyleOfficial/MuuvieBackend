package org.mobyle.domain.usecase.users

import org.mobyle.domain.model.ProfileStats

/** The counters of a profile (F5.1), limited to what the viewer can see. */
class GetProfileStats(private val access: ProfileAccess) {
    operator fun invoke(viewerId: String?, idOrHandle: String): ProfileStats =
        access.profile(viewerId, access.resolveUserId(idOrHandle)).stats
}
