package org.mobyle.data.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.mobyle.data.local.user.UserDatabaseDataSource
import org.mobyle.domain.model.MovieReviewDraft
import org.mobyle.domain.model.UserActivity
import org.mobyle.domain.repository.MoviesRepository
import org.mobyle.domain.repository.UserActivitiesRepository
import org.mobyle.model.UserActivityListing
import org.slf4j.LoggerFactory

class UserActivitiesRepositoryImpl(
    private val userDatabaseDataSource: UserDatabaseDataSource,
    private val moviesRepository: MoviesRepository
) : UserActivitiesRepository {

    private val log = LoggerFactory.getLogger(UserActivitiesRepositoryImpl::class.java)

    override suspend fun getUserActivities(userId: String): List<UserActivity> {
        val (activities, _) = userDatabaseDataSource.getFriendsActivities(userId, page = 1, pageSize = 20)
        return activities
    }

    override suspend fun getFriendsActivities(currentUserId: String, page: Int): UserActivityListing {
        val pageSize = 10
        val (activities, totalPages) = userDatabaseDataSource.getFriendsActivities(currentUserId, page, pageSize)

        return UserActivityListing(
            totalPages = totalPages,
            totalResults = activities.size,
            activities = activities
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

        // Fetch and cache full movie details from TMDB in background
        CoroutineScope(Dispatchers.IO).launch {
            try {
                moviesRepository.getMovieDetail(draft.movieId, userId)
            } catch (e: Exception) {
                log.warn("Background movie enrichment failed for tmdbId=${draft.movieId}: ${e.message}")
            }
        }
    }
}
