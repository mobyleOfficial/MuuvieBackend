package org.mobyle.domain.usecase.movies

import kotlinx.coroutines.runBlocking
import org.mobyle.domain.model.MovieList
import org.mobyle.domain.repository.MoviesRepository

class CreateMovieList(private val repository: MoviesRepository) {
    operator fun invoke(userId: String, name: String, description: String?): MovieList = runBlocking {
        repository.createMovieList(userId, name, description)
    }
}
