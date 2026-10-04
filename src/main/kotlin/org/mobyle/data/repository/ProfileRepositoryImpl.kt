package org.mobyle.data.repository

import org.mobyle.data.local.user.UserDatabaseDataSource
import org.mobyle.domain.model.*
import org.mobyle.domain.repository.ProfileRepository

class ProfileRepositoryImpl(
    private val userDatabaseDataSource: UserDatabaseDataSource
) : ProfileRepository {

    companion object {
        private val mockProfiles = mapOf(
            "user-001" to PublicProfile(
                id = "user-001",
                displayName = "Alice Martins",
                initials = "AM",
                bio = "Film enthusiast and critic",
                moviesWatched = listOf(
                    ProfileWatchedMovie(550, "Fight Club", "/pB8BM7pdSp6B6Ih7QZ4DrQ3PmJK.jpg"),
                    ProfileWatchedMovie(278, "The Shawshank Redemption", "/q6725aR8Zs4IwsER1POIPZeroA8.jpg"),
                    ProfileWatchedMovie(238, "The Godfather", "/3bhkrj58Vtu7enYsRolD1fZQeQA.jpg")
                ),
                following = listOf(
                    ProfileUser("user-002", "Bruno Carvalho", "BC"),
                    ProfileUser("user-003", "Camila Torres", "CT")
                ),
                followers = listOf(
                    ProfileUser("user-004", "Diego Ferreira", "DF"),
                    ProfileUser("user-005", "Elena Souza", "ES")
                ),
                favoriteMovies = listOf(
                    ProfileFavoriteMovie(550, "Fight Club"),
                    ProfileFavoriteMovie(278, "The Shawshank Redemption"),
                    ProfileFavoriteMovie(238, "The Godfather")
                ),
                recentActivities = listOf(
                    ProfileRecentActivity("watched", "Inception", "2 days ago"),
                    ProfileRecentActivity("reviewed", "The Dark Knight", "5 days ago"),
                    ProfileRecentActivity("added_to_watchlist", "Parasite", "1 week ago")
                ),
                watchlist = listOf(
                    ProfileWatchlistItem(550, "Interstellar"),
                    ProfileWatchlistItem(278, "The Matrix"),
                    ProfileWatchlistItem(238, "Blade Runner 2049")
                )
            ),
            "user-002" to PublicProfile(
                id = "user-002",
                displayName = "Bruno Carvalho",
                initials = "BC",
                bio = "Movie geek, always discovering new films",
                moviesWatched = listOf(
                    ProfileWatchedMovie(278, "The Shawshank Redemption", "/q6725aR8Zs4IwsER1POIPZeroA8.jpg"),
                    ProfileWatchedMovie(238, "The Godfather", "/3bhkrj58Vtu7enYsRolD1fZQeQA.jpg")
                ),
                following = listOf(ProfileUser("user-001", "Alice Martins", "AM")),
                followers = listOf(
                    ProfileUser("user-001", "Alice Martins", "AM"),
                    ProfileUser("user-003", "Camila Torres", "CT")
                ),
                favoriteMovies = listOf(
                    ProfileFavoriteMovie(278, "The Shawshank Redemption")
                ),
                recentActivities = listOf(
                    ProfileRecentActivity("reviewed", "The Godfather Part II", "3 days ago")
                ),
                watchlist = listOf(
                    ProfileWatchlistItem(12, "Finding Nemo")
                )
            )
        )
    }

    override suspend fun getUserProfile(): UserProfile {
        return UserProfile(username = "")
    }

    override suspend fun updateUserProfile(profile: UserProfile) {
        userDatabaseDataSource.updateUser(
            userExternalId = profile.id,
            username = profile.username,
            bio = profile.bio.takeIf { it.isNotBlank() },
            avatarUrl = profile.photoUrl.takeIf { it.isNotBlank() }
        )
    }

    override suspend fun getPublicProfile(userId: String): PublicProfile {
        return mockProfiles[userId] ?: PublicProfile(
            id = userId,
            displayName = "Unknown User",
            initials = "??"
        )
    }
}
