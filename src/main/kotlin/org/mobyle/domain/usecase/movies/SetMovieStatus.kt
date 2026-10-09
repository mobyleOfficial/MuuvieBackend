package org.mobyle.domain.usecase.movies

import org.mobyle.domain.repository.MoviesRepository

class SetMovieStatus(private val repository: MoviesRepository) {
    suspend operator fun invoke(userId: String, movieId: Long, status: String) {
        repository.setMovieStatus(userId, movieId, status)
    }
}
