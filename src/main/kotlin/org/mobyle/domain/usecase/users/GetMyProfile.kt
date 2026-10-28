package org.mobyle.domain.usecase.users

import org.mobyle.domain.model.Profile
import org.mobyle.domain.repository.UserProfileRepository

/** The own profile (GET /users/me), with the import flag the header shows while a Filmow import runs. */
class GetMyProfile(private val repository: UserProfileRepository, private val access: ProfileAccess) {
    operator fun invoke(userId: String): Profile =
        access.profile(userId, userId).copy(isScraping = repository.isImporting(userId))
}
