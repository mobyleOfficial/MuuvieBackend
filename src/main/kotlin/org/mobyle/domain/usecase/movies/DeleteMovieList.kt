package org.mobyle.domain.usecase.movies

import kotlinx.coroutines.runBlocking
import org.mobyle.domain.repository.MoviesRepository

class DeleteMovieList(private val repository: MoviesRepository) {
    operator fun invoke(userId: String, listId: Int) = runBlocking {
        repository.deleteMovieList(userId, listId)
    }
}
