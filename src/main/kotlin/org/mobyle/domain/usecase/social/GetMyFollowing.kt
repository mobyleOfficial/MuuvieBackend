package org.mobyle.domain.usecase.social

import kotlinx.coroutines.runBlocking
import org.mobyle.domain.model.SocialUser
import org.mobyle.domain.repository.ProfileRepository

class GetMyFollowing(private val repository: ProfileRepository) {
    operator fun invoke(currentUserId: String): List<SocialUser> = runBlocking {
        repository.getMyFollowing(currentUserId)
    }
}
