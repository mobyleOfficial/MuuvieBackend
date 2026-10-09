package org.mobyle.domain.usecase.movies

import org.mobyle.domain.repository.MoviesRepository

class RateMovie(private val repository: MoviesRepository) {
    suspend operator fun invoke(userId: String, movieId: Long, rating: Float) {
        repository.rateMovie(userId, movieId, rating)
    }
}
