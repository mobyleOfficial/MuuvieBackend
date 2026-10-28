package org.mobyle.domain.policy

import org.mobyle.domain.model.ImportedMovie
import org.mobyle.domain.model.MovieCandidate
import org.mobyle.domain.model.MovieCandidateFacts
import java.text.Normalizer

/**
 * How an imported movie is matched with a TMDB search result (F3.4).
 *
 * The score mixes the title (exact after normalizing accents, case and punctuation, or the
 * share of common words) and the year (exact, or one year off for festival vs. release dates).
 * A match is automatic only when the title is exact and the year agrees; anything else goes
 * to the confirmation queue with the best candidates.
 *
 * When that is not enough, the director confirms a candidate (filmow-movie-enrichment): same
 * director, year at most one off, and a title (localized, English, original or alternative)
 * that is exact or shares most of its words. Only a single confirmed candidate is used.
 */
object MovieMatchPolicy {
    const val MAX_CANDIDATES = 3

    private const val TITLE_WEIGHT = 0.7
    private const val YEAR_WEIGHT = 0.3

    fun score(movie: ImportedMovie, candidateTitles: List<String?>, candidateYear: Int?): Double {
        val titles = movie.titles().map(::normalize).filter { it.isNotEmpty() }
        val others = candidateTitles.filterNotNull().map(::normalize).filter { it.isNotEmpty() }
        val titleScore = titles.flatMap { a -> others.map { b -> titleSimilarity(a, b) } }.maxOrNull() ?: 0.0
        return TITLE_WEIGHT * titleScore + YEAR_WEIGHT * yearSimilarity(movie.year, candidateYear)
    }

    /** The candidate to use without asking, or null when the user has to confirm. */
    fun automaticMatch(movie: ImportedMovie, candidates: List<MovieCandidate>): MovieCandidate? {
        val exact = candidates.filter { it.score >= TITLE_WEIGHT + YEAR_WEIGHT - EPSILON }
        if (exact.size == 1) return exact.single()
        // Without a year on the source, an exact title is enough only when it is unique.
        if (movie.year == null) {
            val exactTitle = candidates.filter { it.score >= TITLE_WEIGHT + YEAR_WEIGHT * UNKNOWN_YEAR - EPSILON }
            if (exactTitle.size == 1) return exactTitle.single()
        }
        return null
    }

    /** The candidate confirmed by the director, or null when none or more than one is. */
    fun directorMatch(movie: ImportedMovie, candidates: List<Pair<MovieCandidate, MovieCandidateFacts>>): MovieCandidate? =
        candidates.filter { (_, facts) -> confirmedByDirector(movie, facts) }
            .map { it.first }
            .distinctBy { it.tmdbId }
            .singleOrNull()

    fun confirmedByDirector(movie: ImportedMovie, facts: MovieCandidateFacts): Boolean {
        val director = movie.director?.let(::normalize)?.takeIf { it.isNotEmpty() } ?: return false
        if (facts.directors.none { normalize(it) == director }) return false
        if (movie.year != null && facts.year != null && kotlin.math.abs(movie.year - facts.year) > 1) return false
        val titles = movie.titles().map(::normalize).filter { it.isNotEmpty() }
        val others = facts.titles.map(::normalize).filter { it.isNotEmpty() }
        val best = titles.flatMap { a -> others.map { b -> titleSimilarity(a, b) } }.maxOrNull() ?: 0.0
        return best >= DIRECTOR_TITLE_MIN
    }

    private fun ImportedMovie.titles() = listOfNotNull(title, originalTitle, localTitle).distinct()

    fun normalize(title: String): String =
        Normalizer.normalize(title.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

    private fun titleSimilarity(a: String, b: String): Double {
        if (a == b) return 1.0
        val wordsA = a.split(' ').toSet()
        val wordsB = b.split(' ').toSet()
        return (wordsA intersect wordsB).size.toDouble() / (wordsA union wordsB).size * PARTIAL_TITLE
    }

    private fun yearSimilarity(year: Int?, other: Int?): Double = when {
        year == null || other == null -> UNKNOWN_YEAR
        year == other -> 1.0
        kotlin.math.abs(year - other) == 1 -> NEAR_YEAR
        else -> 0.0
    }

    // A partial title never reaches an exact one.
    private const val PARTIAL_TITLE = 0.8
    private const val UNKNOWN_YEAR = 0.5
    private const val NEAR_YEAR = 0.5
    private const val EPSILON = 1e-9
    // Half of the words in common (0.5 × PARTIAL_TITLE) is enough when the director agrees.
    private const val DIRECTOR_TITLE_MIN = 0.4
}
