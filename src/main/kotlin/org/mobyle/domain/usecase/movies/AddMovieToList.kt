package org.mobyle.domain.usecase.movies

import kotlinx.coroutines.runBlocking
import org.mobyle.domain.repository.MoviesRepository

class AddMovieToList(private val repository: MoviesRepository) {
    operator fun invoke(userId: String, listId: Int, movieId: Long) = runBlocking {
        repository.addMovieToList(userId, listId, movieId)
    }
}
