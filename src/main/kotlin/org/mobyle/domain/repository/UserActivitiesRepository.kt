package org.mobyle.domain.repository

import org.mobyle.domain.model.MovieReviewDraft
import org.mobyle.domain.model.UserActivity
import org.mobyle.model.UserActivityListing

interface UserActivitiesRepository {
    suspend fun getUserActivities(userId: String): List<UserActivity>
    suspend fun getFriendsActivities(currentUserId: String, page: Int): UserActivityListing
    suspend fun submitReview(userId: String, draft: MovieReviewDraft)
}
