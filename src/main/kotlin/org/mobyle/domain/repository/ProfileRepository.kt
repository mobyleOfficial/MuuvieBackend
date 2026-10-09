package org.mobyle.domain.repository

import org.mobyle.domain.model.ProfileUser
import org.mobyle.domain.model.PublicProfile
import org.mobyle.domain.model.SocialUser
import org.mobyle.domain.model.UserProfile

interface ProfileRepository {
    suspend fun getUserProfile(): UserProfile
    suspend fun updateUserProfile(profile: UserProfile)
    suspend fun getPublicProfile(userId: String, currentUserId: String? = null): PublicProfile

    // Social
    suspend fun followUser(followerId: String, followedId: String): Boolean
    suspend fun unfollowUser(followerId: String, followedId: String): Boolean
    suspend fun isFollowing(followerId: String, followedId: String): Boolean
    suspend fun getFollowers(userId: String, page: Int = 1, pageSize: Int = 50): List<ProfileUser>
    suspend fun getFollowing(userId: String, page: Int = 1, pageSize: Int = 50): List<ProfileUser>
    suspend fun getMyFollowing(currentUserId: String): List<SocialUser>
    suspend fun searchUsers(query: String, currentUserId: String): List<SocialUser>
}
