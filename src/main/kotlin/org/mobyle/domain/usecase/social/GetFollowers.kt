package org.mobyle.domain.usecase.social

import kotlinx.coroutines.runBlocking
import org.mobyle.domain.model.ProfileUser
import org.mobyle.domain.repository.ProfileRepository

class GetFollowers(private val repository: ProfileRepository) {
    operator fun invoke(userId: String, page: Int = 1, pageSize: Int = 50): List<ProfileUser> = runBlocking {
        repository.getFollowers(userId, page, pageSize)
    }
}
