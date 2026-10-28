package org.mobyle.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * What a movie must have to complete a challenge (B7): every rule in [all] must match.
 * `{"all":[{"field":"genre","op":"in","value":[27]},{"field":"decade","op":"in","value":[1970]}]}`
 * is "a horror movie from the 70s". ChallengeCriteriaMatcher validates and evaluates it.
 */
@Serializable
data class ChallengeCriteria(val all: List<CriteriaRule>)

/** One condition of a [ChallengeCriteria]; [field] and [op] come from [CriteriaField]. */
@Serializable
data class CriteriaRule(val field: String, val op: String, val value: JsonElement) {
    /** The values of an `in` rule, or the pair of a `between` rule. */
    fun ints(): List<Int> = (value as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }.orEmpty()

    fun strings(): List<String> = (value as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.orEmpty()

    fun int(): Int? = (value as? JsonPrimitive)?.intOrNull

    fun double(): Double? = (value as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
}

/** The fields a criteria rule can test, each with the one operator it takes. */
enum class CriteriaField(val wireValue: String, val op: String) {
    GENRE("genre", "in"), // TMDB genre ids
    RELEASE_YEAR("release_year", "between"), // [from, to], inclusive
    DECADE("decade", "in"), // [1970, 1980]
    COUNTRY("country", "in"), // ISO 3166-1, production countries
    ORIGINAL_LANGUAGE("original_language", "in"), // ISO 639-1
    DIRECTOR("director", "in"), // TMDB person ids
    CAST("cast", "in"), // TMDB person ids, top 20 billed
    RUNTIME_MIN("runtime_min", "gte"), // minutes
    RUNTIME_MAX("runtime_max", "lte"), // minutes
    KEYWORD("keyword", "in"), // TMDB keyword ids
    VOTE_AVERAGE_MIN("vote_average_min", "gte"); // TMDB score, 0-10

    companion object {
        fun fromWireValue(value: String): CriteriaField? = entries.firstOrNull { it.wireValue == value }
    }
}

/** The metadata of a movie that criteria read, from the enriched catalog. */
data class MovieFacts(
    val movieId: Long,
    val genreIds: Set<Int>,
    val year: Int?,
    val countries: Set<String>,
    val originalLanguage: String?,
    val directorIds: Set<Int>,
    val castIds: Set<Int>,
    val runtime: Int?,
    val keywordIds: Set<Int>,
    val voteAverage: Double?
)
