package org.mobyle.domain.usecase.activities

import kotlinx.coroutines.runBlocking
import org.mobyle.domain.model.MovieReviewDraft
import org.mobyle.domain.repository.UserActivitiesRepository

class SubmitReview(private val repository: UserActivitiesRepository) {
    operator fun invoke(userId: String, draft: MovieReviewDraft) = runBlocking {
        repository.submitReview(userId, draft)
    }
}
