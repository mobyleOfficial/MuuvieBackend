package org.mobyle.domain.usecase.movies

import org.mobyle.domain.repository.MoviesRepository

class LikeMovie(private val repository: MoviesRepository) {
    suspend operator fun invoke(userId: String, movieId: Long) {
        repository.likeMovie(userId, movieId)
    }
}
