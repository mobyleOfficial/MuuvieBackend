package org.mobyle.domain.usecase.movies

import kotlinx.coroutines.runBlocking
import org.mobyle.domain.model.MovieTraits
import org.mobyle.domain.model.NotFoundException
import org.mobyle.domain.model.SimilarMovies
import org.mobyle.domain.repository.MoviesRepository

/**
 * The similar movies of one catalog movie, without the rest of its detail, sorted by how similar
 * they are (see [SimilarityScorer]): the payload the Universo de Filmes fetches on every jump
 * (research/features/movie-universe/roadmap.md).
 */
class GetSimilarMovies(private val repository: MoviesRepository) {
    operator fun invoke(movieId: Long): SimilarMovies = runBlocking {
        val similarMovies = repository.getSimilarMovies(movieId) ?: throw NotFoundException("Movie not found")
        if (similarMovies.isEmpty()) return@runBlocking SimilarMovies(movieId = movieId, results = emptyList())

        val similarMovieIds = similarMovies.map { movie -> movie.id }
        val traitsById = repository.getMovieTraits(similarMovieIds + movieId)
        val affinities = SimilarityScorer.score(
            source = traitsById[movieId] ?: MovieTraits(),
            similarMovieIds = similarMovieIds,
            traitsById = traitsById
        )

        // Stable sort: equal scores keep TMDB's order.
        val ranked = similarMovies.zip(affinities).sortedByDescending { (_, affinity) -> affinity.score }
        SimilarMovies(
            movieId = movieId,
            results = ranked.map { (movie, _) -> movie },
            affinities = ranked.map { (_, affinity) -> affinity }
        )
    }
}
