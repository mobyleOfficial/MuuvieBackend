package org.mobyle.data.repository

import org.mobyle.data.local.user.UserDatabaseDataSource
import org.mobyle.domain.model.SocialUser
import org.mobyle.domain.model.UserProfile
import org.mobyle.domain.repository.ProfileRepository

class ProfileRepositoryImpl(
    private val userDatabaseDataSource: UserDatabaseDataSource
) : ProfileRepository {

    override suspend fun updateUserProfile(profile: UserProfile) {
        userDatabaseDataSource.updateUser(
            userExternalId = profile.id,
            username = profile.username,
            bio = profile.bio.takeIf { it.isNotBlank() },
            avatarUrl = profile.photoUrl.takeIf { it.isNotBlank() }
        )
    }

    override suspend fun isUsernameTaken(username: String, exceptUserId: String): Boolean =
        userDatabaseDataSource.isUsernameTaken(username, exceptUserId)

    override suspend fun searchUsers(query: String, currentUserId: String): List<SocialUser> =
        userDatabaseDataSource.searchUsers(query, currentUserId)
}
