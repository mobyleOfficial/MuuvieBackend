package org.mobyle.domain.usecase.social

import kotlinx.coroutines.runBlocking
import org.mobyle.domain.repository.ProfileRepository

class UnfollowUser(private val repository: ProfileRepository) {
    operator fun invoke(followerId: String, followedId: String): Boolean = runBlocking {
        repository.unfollowUser(followerId, followedId)
    }
}
