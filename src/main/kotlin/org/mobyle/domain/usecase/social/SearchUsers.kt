package org.mobyle.domain.usecase.social

import kotlinx.coroutines.runBlocking
import org.mobyle.domain.model.SocialUser
import org.mobyle.domain.repository.ProfileRepository

class SearchUsers(private val repository: ProfileRepository) {
    operator fun invoke(query: String, currentUserId: String): List<SocialUser> = runBlocking {
        repository.searchUsers(query, currentUserId)
    }
}
