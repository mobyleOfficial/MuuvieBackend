package org.mobyle.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class SocialUser(
    val id: String,
    val displayName: String,
    val initials: String,
    val moviesWatchedCount: Int,
    val isFollowing: Boolean
)
