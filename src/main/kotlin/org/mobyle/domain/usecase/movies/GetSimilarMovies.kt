package org.mobyle.domain.usecase.movies

import kotlinx.coroutines.runBlocking
import org.mobyle.domain.model.NotFoundException
import org.mobyle.domain.model.SimilarMovies
import org.mobyle.domain.repository.MoviesRepository

/**
 * The similar movies of one catalog movie, without the rest of its detail: the payload the
 * Universo de Filmes fetches on every jump (research/features/movie-universe/roadmap.md).
 */
class GetSimilarMovies(private val repository: MoviesRepository) {
    operator fun invoke(movieId: Long): SimilarMovies = runBlocking {
        val similarMovies = repository.getSimilarMovies(movieId) ?: throw NotFoundException("Movie not found")
        SimilarMovies(movieId = movieId, results = similarMovies)
    }
}
