package org.mobyle.data.repository

import org.mobyle.data.remote.tmdb.TmdbDataSource
import org.mobyle.domain.model.ImportedMovie
import org.mobyle.domain.model.MovieCandidate
import org.mobyle.domain.policy.MovieMatchPolicy
import org.mobyle.domain.repository.MovieCandidateSearch

/** Searches TMDB by the title (and the original title when it differs), filtered by year when known. */
class TmdbMovieCandidateSearch(private val tmdbDataSource: TmdbDataSource) : MovieCandidateSearch {

    override suspend fun candidates(movie: ImportedMovie): List<MovieCandidate> {
        val queries = listOfNotNull(movie.originalTitle, movie.title).distinct()
        val results = queries.flatMap { query ->
            val byYear = movie.year?.let { tmdbDataSource.searchMovies(query, 1, year = it).results }.orEmpty()
            byYear.ifEmpty { tmdbDataSource.searchMovies(query, 1).results }
        }
        return results
            .distinctBy { it.id }
            .map { result ->
                val year = result.releaseDate?.take(4)?.toIntOrNull()
                MovieCandidate(
                    tmdbId = result.id,
                    title = result.title ?: result.originalTitle.orEmpty(),
                    originalTitle = result.originalTitle,
                    year = year,
                    posterPath = result.posterPath,
                    score = MovieMatchPolicy.score(movie, listOf(result.title, result.originalTitle), year)
                )
            }
            .sortedByDescending { it.score }
            .take(MovieMatchPolicy.MAX_CANDIDATES)
    }
}
