package org.mobyle.data.repository

import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.mobyle.data.local.database.MoviesTable
import org.mobyle.data.local.database.UserMoviesTable
import org.mobyle.data.local.database.UsersTable
import org.mobyle.data.local.user.UserDatabaseDataSource
import org.mobyle.domain.model.*
import org.mobyle.domain.repository.ProfileRepository

class ProfileRepositoryImpl(
    private val userDatabaseDataSource: UserDatabaseDataSource
) : ProfileRepository {

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

    override suspend fun getPublicProfile(userId: String, currentUserId: String?): PublicProfile {
        val user = userDatabaseDataSource.findByExternalId(userId)
            ?: return PublicProfile(id = userId, displayName = "Unknown User", initials = "??")

        val initials = computeInitials(user.username)

        val moviesWatched = getProfileWatchedMovies(userId, limit = 10)
        val favoriteMovies = getProfileFavoriteMovies(userId, limit = 10)
        val watchlist = getProfileWatchlist(userId, limit = 10)
        val following = userDatabaseDataSource.getFollowing(userId)
        val followers = userDatabaseDataSource.getFollowers(userId)
        val recentActivities = getProfileRecentActivities(userId, limit = 5)
        val isFollowing = if (currentUserId != null) {
            userDatabaseDataSource.isFollowing(currentUserId, userId)
        } else false

        return PublicProfile(
            id = userId,
            displayName = user.username,
            initials = initials,
            bio = user.bio,
            moviesWatched = moviesWatched,
            following = following,
            followers = followers,
            favoriteMovies = favoriteMovies,
            recentActivities = recentActivities,
            watchlist = watchlist,
            isFollowing = isFollowing
        )
    }

    // ── Social ──────────────────────────────────────────────────────────────

    override suspend fun followUser(followerId: String, followedId: String): Boolean =
        userDatabaseDataSource.followUser(followerId, followedId)

    override suspend fun unfollowUser(followerId: String, followedId: String): Boolean =
        userDatabaseDataSource.unfollowUser(followerId, followedId)

    override suspend fun isFollowing(followerId: String, followedId: String): Boolean =
        userDatabaseDataSource.isFollowing(followerId, followedId)

    override suspend fun getFollowers(userId: String, page: Int, pageSize: Int): List<ProfileUser> =
        userDatabaseDataSource.getFollowers(userId, page, pageSize)

    override suspend fun getFollowing(userId: String, page: Int, pageSize: Int): List<ProfileUser> =
        userDatabaseDataSource.getFollowing(userId, page, pageSize)

    override suspend fun getMyFollowing(currentUserId: String): List<SocialUser> =
        userDatabaseDataSource.getMyFollowing(currentUserId)

    override suspend fun searchUsers(query: String, currentUserId: String): List<SocialUser> =
        userDatabaseDataSource.searchUsers(query, currentUserId)

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun getProfileWatchedMovies(userExternalId: String, limit: Int): List<ProfileWatchedMovie> {
        return transaction {
            val userDbId = resolveUserDbId(userExternalId) ?: return@transaction emptyList()

            (UserMoviesTable innerJoin MoviesTable)
                .selectAll()
                .where {
                    (UserMoviesTable.userId eq userDbId) and
                        (UserMoviesTable.status eq "watched")
                }
                .orderBy(UserMoviesTable.watchedAt, SortOrder.DESC)
                .limit(limit)
                .map { row ->
                    ProfileWatchedMovie(
                        id = row[MoviesTable.tmdbId] ?: row[MoviesTable.id].value.toInt(),
                        title = row[MoviesTable.title],
                        posterPath = row[MoviesTable.posterPath]
                    )
                }
        }
    }

    private fun getProfileFavoriteMovies(userExternalId: String, limit: Int): List<ProfileFavoriteMovie> {
        return transaction {
            val userDbId = resolveUserDbId(userExternalId) ?: return@transaction emptyList()

            (UserMoviesTable innerJoin MoviesTable)
                .selectAll()
                .where {
                    (UserMoviesTable.userId eq userDbId) and
                        (UserMoviesTable.isFavorite eq true)
                }
                .orderBy(UserMoviesTable.updatedAt, SortOrder.DESC)
                .limit(limit)
                .map { row ->
                    ProfileFavoriteMovie(
                        id = row[MoviesTable.tmdbId] ?: row[MoviesTable.id].value.toInt(),
                        title = row[MoviesTable.title]
                    )
                }
        }
    }

    private fun getProfileWatchlist(userExternalId: String, limit: Int): List<ProfileWatchlistItem> {
        return transaction {
            val userDbId = resolveUserDbId(userExternalId) ?: return@transaction emptyList()

            (UserMoviesTable innerJoin MoviesTable)
                .selectAll()
                .where {
                    (UserMoviesTable.userId eq userDbId) and
                        (UserMoviesTable.status eq "want_to_watch")
                }
                .orderBy(UserMoviesTable.updatedAt, SortOrder.DESC)
                .limit(limit)
                .map { row ->
                    ProfileWatchlistItem(
                        id = row[MoviesTable.tmdbId] ?: row[MoviesTable.id].value.toInt(),
                        title = row[MoviesTable.title]
                    )
                }
        }
    }

    private fun getProfileRecentActivities(userExternalId: String, limit: Int): List<ProfileRecentActivity> {
        return transaction {
            val userDbId = resolveUserDbId(userExternalId) ?: return@transaction emptyList()

            (UserMoviesTable innerJoin MoviesTable)
                .selectAll()
                .where { UserMoviesTable.userId eq userDbId }
                .orderBy(UserMoviesTable.updatedAt, SortOrder.DESC)
                .limit(limit)
                .map { row ->
                    val status = row[UserMoviesTable.status]
                    val hasReview = !row[UserMoviesTable.review].isNullOrBlank()
                    val movieTitle = row[MoviesTable.title]

                    val action = when {
                        hasReview -> "Reviewed"
                        status == "watched" -> "Watched"
                        status == "want_to_watch" -> "Added to watchlist"
                        else -> "Watched"
                    }

                    val time = row[UserMoviesTable.watchedAt]?.let {
                        formatRelativeTime(it.toEpochMilliseconds())
                    } ?: "Recently"

                    ProfileRecentActivity(action = action, movie = movieTitle, time = time)
                }
        }
    }

    private fun resolveUserDbId(userExternalId: String): Long? {
        return UsersTable.selectAll()
            .where { UsersTable.externalId eq userExternalId }
            .firstOrNull()
            ?.get(UsersTable.id)?.value
    }

    private fun computeInitials(username: String): String {
        val parts = username.split(Regex("[_\\s.]+")).filter { it.isNotBlank() }
        return when {
            parts.size >= 2 -> "${parts[0].first().uppercaseChar()}${parts[1].first().uppercaseChar()}"
            parts.size == 1 && parts[0].length >= 2 -> parts[0].take(2).uppercase()
            parts.size == 1 -> parts[0].first().uppercaseChar().toString()
            else -> "??"
        }
    }

    private fun formatRelativeTime(epochMs: Long): String {
        val diffMs = System.currentTimeMillis() - epochMs
        val diffMinutes = diffMs / 60_000
        val diffHours = diffMinutes / 60
        val diffDays = diffHours / 24
        return when {
            diffMinutes < 60 -> "${diffMinutes}m ago"
            diffHours < 24 -> "${diffHours}h ago"
            diffDays == 1L -> "1d ago"
            diffDays < 7 -> "${diffDays}d ago"
            else -> "${diffDays / 7}w ago"
        }
    }
}
