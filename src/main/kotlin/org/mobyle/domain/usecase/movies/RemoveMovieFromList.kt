package org.mobyle.domain.usecase.movies

import kotlinx.coroutines.runBlocking
import org.mobyle.domain.repository.MoviesRepository

class RemoveMovieFromList(private val repository: MoviesRepository) {
    operator fun invoke(userId: String, listId: Int, movieId: Long) = runBlocking {
        repository.removeMovieFromList(userId, listId, movieId)
    }
}
