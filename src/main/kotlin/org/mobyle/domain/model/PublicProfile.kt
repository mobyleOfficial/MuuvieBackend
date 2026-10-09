package org.mobyle.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class PublicProfile(
    val id: String,
    val displayName: String,
    val initials: String,
    val bio: String? = null,
    val moviesWatched: List<ProfileWatchedMovie> = emptyList(),
    val following: List<ProfileUser> = emptyList(),
    val followers: List<ProfileUser> = emptyList(),
    val favoriteMovies: List<ProfileFavoriteMovie> = emptyList(),
    val recentActivities: List<ProfileRecentActivity> = emptyList(),
    val watchlist: List<ProfileWatchlistItem> = emptyList(),
    val isFollowing: Boolean = false
)

@Serializable
data class ProfileUser(
    val id: String,
    val displayName: String,
    val initials: String
)

@Serializable
data class ProfileFavoriteMovie(
    val id: Int,
    val title: String
)

@Serializable
data class ProfileWatchedMovie(
    val id: Int,
    val title: String,
    val posterPath: String? = null
)

@Serializable
data class ProfileWatchlistItem(
    val id: Int,
    val title: String
)

@Serializable
data class ProfileRecentActivity(
    val action: String,
    val movie: String,
    val time: String
)
