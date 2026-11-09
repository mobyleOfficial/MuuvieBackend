package org.mobyle.domain.usecase.movies

import org.mobyle.domain.model.MovieTraits
import org.mobyle.domain.model.SimilarityAffinity

/**
 * Scores how similar each movie in a TMDB similar list is to its source movie.
 *
 * Three signals, each from 0 to 1:
 * - **rank**: TMDB's own order, from 0.7 for the first movie down to 0.3 for the last, so that
 *   a movie with nothing else known stays near a neutral 0.5;
 * - **genres** and **keywords**: the overlap coefficient `|A ∩ B| / min(|A|, |B|)`.
 *
 * A signal only counts when both movies have data for it; the weights of the missing ones are
 * shared out among the others. Movies not yet enriched therefore get a rank-only score.
 */
object SimilarityScorer {

    private const val rankWeight = 0.2
    private const val genreWeight = 0.35
    private const val keywordWeight = 0.45

    fun score(
        source: MovieTraits,
        similarMovieIds: List<Long>,
        traitsById: Map<Long, MovieTraits>
    ): List<SimilarityAffinity> = similarMovieIds.mapIndexed { index, similarMovieId ->
        val similar = traitsById[similarMovieId] ?: MovieTraits()
        val sharedGenres = source.genres intersect similar.genres
        val sharedKeywords = source.keywords intersect similar.keywords

        val weightedSignals = buildList {
            add(rankWeight to rankSignal(index, similarMovieIds.size))
            if (source.genres.isNotEmpty() && similar.genres.isNotEmpty()) {
                add(genreWeight to overlap(sharedGenres.size, source.genres.size, similar.genres.size))
            }
            if (source.keywords.isNotEmpty() && similar.keywords.isNotEmpty()) {
                add(keywordWeight to overlap(sharedKeywords.size, source.keywords.size, similar.keywords.size))
            }
        }
        val totalWeight = weightedSignals.sumOf { (weight, _) -> weight }
        val score = weightedSignals.sumOf { (weight, signal) -> weight * signal } / totalWeight

        SimilarityAffinity(
            movieId = similarMovieId,
            score = roundToThousandths(score),
            sharedGenres = sharedGenres.sorted(),
            sharedKeywords = sharedKeywords.sorted(),
            genres = similar.genres.sorted()
        )
    }

    private fun rankSignal(index: Int, count: Int): Double =
        if (count <= 1) 0.7 else 0.7 - 0.4 * index / (count - 1)

    private fun overlap(sharedCount: Int, sourceCount: Int, similarCount: Int): Double =
        sharedCount.toDouble() / minOf(sourceCount, similarCount)

    private fun roundToThousandths(value: Double): Double = Math.round(value * 1000) / 1000.0
}
