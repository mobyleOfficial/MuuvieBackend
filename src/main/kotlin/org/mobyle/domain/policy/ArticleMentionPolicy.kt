package org.mobyle.domain.policy

/** A movie title quoted in a news article, with the year when the text gives it as "Título (2024)". */
data class MovieMention(val title: String, val year: Int?)

/** A TMDB search result for a mention. */
data class MentionCandidate(
    val tmdbId: Int,
    val title: String?,
    val originalTitle: String?,
    val year: Int?,
    val voteCount: Int
)

/**
 * How the movies a news article cites are found (F5.8). Brazilian outlets quote titles
 * ("Ainda Estou Aqui"), so the quoted spans are the mentions; a mention only links to a
 * movie when a TMDB title matches it exactly after normalizing. Without a year, an exact
 * title also needs some votes, so a quoted common word does not link to an obscure film.
 */
object ArticleMentionPolicy {
    const val MAX_MENTIONS = 8
    const val MIN_CONFIDENCE = 0.8f

    private val QUOTED = Regex("[“\"«]([^”\"»\\n]{2,80})[”\"»](?:\\s*\\((\\d{4})\\))?")
    private const val MAX_WORDS = 8
    private const val MIN_VOTES_WITHOUT_YEAR = 50

    private const val SAME_YEAR = 1.0f
    private const val NEAR_YEAR = 0.9f
    private const val NO_YEAR = 0.8f

    fun mentions(vararg texts: String): List<MovieMention> =
        texts.asSequence()
            .flatMap { QUOTED.findAll(it) }
            .map { MovieMention(it.groupValues[1].trim(), it.groupValues[2].toIntOrNull()) }
            .filter { looksLikeTitle(it.title) }
            // The same title with a year wins over it without one.
            .sortedByDescending { it.year != null }
            .distinctBy { MovieMatchPolicy.normalize(it.title) }
            .take(MAX_MENTIONS)
            .toList()

    /** The candidate the mention links to and the confidence, or null below [MIN_CONFIDENCE]. */
    fun match(mention: MovieMention, candidates: List<MentionCandidate>): Pair<MentionCandidate, Float>? {
        val title = MovieMatchPolicy.normalize(mention.title)
        val exact = candidates.filter { candidate ->
            listOfNotNull(candidate.title, candidate.originalTitle).any { MovieMatchPolicy.normalize(it) == title }
        }
        if (mention.year != null) {
            exact.firstOrNull { it.year == mention.year }?.let { return it to SAME_YEAR }
            exact.firstOrNull { it.year != null && kotlin.math.abs(it.year - mention.year) == 1 }?.let { return it to NEAR_YEAR }
            return null
        }
        val best = exact.maxByOrNull { it.voteCount } ?: return null
        return if (best.voteCount >= MIN_VOTES_WITHOUT_YEAR) best to NO_YEAR else null
    }

    // Speech quotes end with a period or run long; titles start with a capital or a digit
    // ("Mamma Mia!" and questions are titles too, so ! and ? are kept).
    private fun looksLikeTitle(text: String): Boolean {
        if (text.isEmpty() || !(text.first().isUpperCase() || text.first().isDigit())) return false
        if (text.last() in ".,;:…") return false
        return text.split(Regex("\\s+")).size <= MAX_WORDS && MovieMatchPolicy.normalize(text).length >= 2
    }
}
