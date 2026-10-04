package org.mobyle.data.repository

import org.mobyle.data.local.user.UserDatabaseDataSource
import org.mobyle.domain.model.MovieReviewDraft
import org.mobyle.domain.model.UserActivity
import org.mobyle.domain.repository.UserActivitiesRepository
import org.mobyle.model.UserActivityListing
import kotlin.math.ceil

class UserActivitiesRepositoryImpl(
    private val userDatabaseDataSource: UserDatabaseDataSource
) : UserActivitiesRepository {

    companion object {
        private fun getMockActivities(): List<UserActivity> = listOf(
            UserActivity(
                userName = "Alice Martins",
                action = "watched",
                movie = "Inception",
                time = "2026-05-15T14:30:00Z"
            ),
            UserActivity(
                userName = "Bruno Carvalho",
                action = "reviewed",
                movie = "The Dark Knight",
                time = "2026-05-15T10:15:00Z"
            ),
            UserActivity(
                userName = "Camila Torres",
                action = "added_to_watchlist",
                movie = "Parasite",
                time = "2026-05-14T18:45:00Z"
            ),
            UserActivity(
                userName = "Diego Ferreira",
                action = "liked_review",
                movie = "Fight Club",
                time = "2026-05-14T16:20:00Z"
            ),
            UserActivity(
                userName = "Elena Souza",
                action = "watched",
                movie = "The Shawshank Redemption",
                time = "2026-05-14T09:30:00Z"
            ),
            UserActivity(
                userName = "Felipe Lima",
                action = "reviewed",
                movie = "Pulp Fiction",
                time = "2026-05-13T15:00:00Z"
            ),
            UserActivity(
                userName = "Gabriela Nunes",
                action = "added_to_watchlist",
                movie = "Oppenheimer",
                time = "2026-05-13T11:45:00Z"
            ),
            UserActivity(
                userName = "Henrique Costa",
                action = "watched",
                movie = "Interstellar",
                time = "2026-05-12T20:15:00Z"
            )
        )
    }

    override suspend fun getUserActivities(userId: String): List<UserActivity> {
        return getMockActivities().filter { it.userName.contains(userId, ignoreCase = true) }
    }

    override suspend fun getFriendsActivities(page: Int): UserActivityListing {
        val allActivities = getMockActivities()
        val pageSize = 4
        val totalPages = ceil(allActivities.size.toDouble() / pageSize).toInt()
        val startIndex = (page - 1) * pageSize
        val endIndex = minOf(startIndex + pageSize, allActivities.size)

        val paginatedActivities = if (startIndex < allActivities.size) {
            allActivities.subList(startIndex, endIndex)
        } else {
            emptyList()
        }

        return UserActivityListing(
            totalPages = totalPages,
            totalResults = allActivities.size,
            activities = paginatedActivities
        )
    }

    override suspend fun submitReview(userId: String, draft: MovieReviewDraft) {
        userDatabaseDataSource.submitUserReview(
            userExternalId = userId,
            movieTmdbId = draft.movieId,
            movieTitle = draft.movieTitle,
            posterPath = draft.posterPath,
            rating = draft.rating,
            reviewText = draft.reviewBody,
            isFavorite = draft.isFavorite,
            isRewatch = draft.isRewatch
        )
    }
}
