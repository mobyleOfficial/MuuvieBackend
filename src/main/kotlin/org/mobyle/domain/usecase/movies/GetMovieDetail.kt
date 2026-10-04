package org.mobyle.domain.usecase.movies

import kotlinx.coroutines.runBlocking
import org.mobyle.domain.model.MovieDetail
import org.mobyle.domain.repository.MoviesRepository

class GetMovieDetail(private val repository: MoviesRepository) {
    operator fun invoke(movieId: Int, userId: String? = null): MovieDetail = runBlocking {
        repository.getMovieDetail(movieId, userId)
    }
}
