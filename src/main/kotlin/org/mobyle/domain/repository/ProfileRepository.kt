package org.mobyle.domain.repository

import org.mobyle.domain.model.SocialUser
import org.mobyle.domain.model.UserProfile

interface ProfileRepository {
    suspend fun updateUserProfile(profile: UserProfile)
    suspend fun isUsernameTaken(username: String, exceptUserId: String): Boolean
    suspend fun searchUsers(query: String, currentUserId: String): List<SocialUser>
}
