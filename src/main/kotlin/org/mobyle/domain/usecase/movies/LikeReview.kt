package org.mobyle.domain.usecase.movies

import org.mobyle.domain.repository.MoviesRepository

class LikeReview(private val repository: MoviesRepository) {
    suspend operator fun invoke(userId: String, reviewId: String) {
        repository.likeReview(userId, reviewId)
    }
}
