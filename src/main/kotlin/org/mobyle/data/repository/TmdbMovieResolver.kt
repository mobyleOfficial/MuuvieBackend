package org.mobyle.data.repository

import org.mobyle.data.remote.tmdb.TmdbDataSource
import org.mobyle.domain.model.ImportedMovie
import org.mobyle.domain.model.MovieCandidateFacts
import org.mobyle.domain.model.MovieResolution
import org.mobyle.domain.model.vocabulary.MovieMatchMethod
import org.mobyle.domain.policy.MovieMatchPolicy
import org.mobyle.domain.repository.MovieCandidateSearch
import org.mobyle.domain.repository.MovieResolver

class TmdbMovieResolver(
    private val tmdbDataSource: TmdbDataSource,
    private val candidateSearch: MovieCandidateSearch
) : MovieResolver {

    override suspend fun resolve(movie: ImportedMovie): MovieResolution {
        imdbId(movie.imdbUrl)?.let { imdbId ->
            tmdbDataSource.findByImdbId(imdbId).movieResults.firstOrNull()?.let {
                return MovieResolution.Matched(it.id, MovieMatchMethod.IMDB)
            }
        }

        val candidates = candidateSearch.candidates(movie)
        MovieMatchPolicy.automaticMatch(movie, candidates)?.let {
            return MovieResolution.Matched(it.tmdbId, MovieMatchMethod.TITLE)
        }

        // Only worth the extra calls when the source knows the director.
        if (!movie.director.isNullOrBlank() && candidates.isNotEmpty()) {
            val withFacts = candidates.map { it to facts(it.tmdbId) }
            MovieMatchPolicy.directorMatch(movie, withFacts)?.let {
                return MovieResolution.Matched(it.tmdbId, MovieMatchMethod.DIRECTOR)
            }
        }
        return MovieResolution.Unmatched(candidates)
    }

    private suspend fun facts(tmdbId: Int): MovieCandidateFacts {
        val response = tmdbDataSource.getMovieFacts(tmdbId)
        return MovieCandidateFacts(
            titles = (listOf(response.title, response.originalTitle) +
                response.alternativeTitles?.titles.orEmpty().map { it.title }).filterNotNull().distinct(),
            year = response.releaseDate?.take(4)?.toIntOrNull(),
            directors = response.credits?.crew.orEmpty().filter { it.job == "Director" }.mapNotNull { it.name }
        )
    }

    companion object {
        /** tt followed by digits anywhere in the URL, e.g. https://www.imdb.com/title/tt0118694/ */
        fun imdbId(imdbUrl: String?): String? =
            imdbUrl?.takeIf { it.isNotBlank() }?.let { Regex("(tt\\d+)").find(it)?.value }
    }
}
