package org.mobyle.domain.policy

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.mobyle.domain.model.ChallengeCriteria
import org.mobyle.domain.model.CriteriaField
import org.mobyle.domain.model.CriteriaRule
import org.mobyle.domain.model.MovieFacts
import org.mobyle.domain.model.ValidationException

/**
 * Checks a challenge's criteria against a movie (F7.5): every rule must match, and a field
 * the catalog does not know yet (no runtime, no countries...) never matches.
 */
object ChallengeCriteriaMatcher {
    const val MAX_RULES = 10
    const val MAX_VALUES = 30
    private val YEARS = 1870..2100

    fun matches(criteria: ChallengeCriteria, facts: MovieFacts): Boolean =
        criteria.all.isNotEmpty() && criteria.all.all { matches(it, facts) }

    private fun matches(rule: CriteriaRule, facts: MovieFacts): Boolean = when (CriteriaField.fromWireValue(rule.field)) {
        CriteriaField.GENRE -> rule.ints().any { it in facts.genreIds }
        CriteriaField.RELEASE_YEAR -> facts.year != null && rule.ints().let { (from, to) -> facts.year in from..to }
        CriteriaField.DECADE -> facts.year != null && (facts.year / 10 * 10) in rule.ints()
        CriteriaField.COUNTRY -> rule.strings().any { it in facts.countries }
        CriteriaField.ORIGINAL_LANGUAGE -> facts.originalLanguage != null && facts.originalLanguage in rule.strings()
        CriteriaField.DIRECTOR -> rule.ints().any { it in facts.directorIds }
        CriteriaField.CAST -> rule.ints().any { it in facts.castIds }
        CriteriaField.RUNTIME_MIN -> facts.runtime != null && facts.runtime >= rule.int()!!
        CriteriaField.RUNTIME_MAX -> facts.runtime != null && facts.runtime > 0 && facts.runtime <= rule.int()!!
        CriteriaField.KEYWORD -> rule.ints().any { it in facts.keywordIds }
        CriteriaField.VOTE_AVERAGE_MIN -> facts.voteAverage != null && facts.voteAverage >= rule.double()!!
        null -> false
    }

    /** Validates criteria sent by a client and returns them normalized (upper-case countries, sorted years...). */
    fun validate(criteria: ChallengeCriteria, field: String = "criteria"): ChallengeCriteria {
        if (criteria.all.isEmpty() || criteria.all.size > MAX_RULES) {
            throw ValidationException(field, "criteria must have between 1 and $MAX_RULES rules")
        }
        return ChallengeCriteria(criteria.all.map { normalize(it, field) })
    }

    private fun normalize(rule: CriteriaRule, field: String): CriteriaRule {
        val known = CriteriaField.fromWireValue(rule.field)
            ?: throw ValidationException(field, "Unknown criteria field '${rule.field}'")
        if (rule.op != known.op) throw ValidationException(field, "'${known.wireValue}' takes the '${known.op}' operator")
        val value = when (known) {
            CriteriaField.GENRE, CriteriaField.DIRECTOR, CriteriaField.CAST, CriteriaField.KEYWORD ->
                ids(rule, field) { it > 0 }
            CriteriaField.DECADE -> ids(rule, field) { it in YEARS && it % 10 == 0 }
            CriteriaField.RELEASE_YEAR -> {
                val years = rule.ints()
                if ((rule.value as? JsonArray)?.size != 2 || years.size != 2 || years.any { it !in YEARS }) {
                    throw ValidationException(field, "release_year takes [from, to] between ${YEARS.first} and ${YEARS.last}")
                }
                JsonArray(years.sorted().map(::JsonPrimitive))
            }
            CriteriaField.COUNTRY -> codes(rule, field, Regex("^[A-Za-z]{2}$")) { it.uppercase() }
            CriteriaField.ORIGINAL_LANGUAGE -> codes(rule, field, Regex("^[A-Za-z]{2,3}$")) { it.lowercase() }
            CriteriaField.RUNTIME_MIN, CriteriaField.RUNTIME_MAX -> {
                val minutes = rule.int()?.takeIf { it in 1..1000 }
                    ?: throw ValidationException(field, "${known.wireValue} takes minutes between 1 and 1000")
                JsonPrimitive(minutes)
            }
            CriteriaField.VOTE_AVERAGE_MIN -> {
                val score = rule.double()?.takeIf { it in 0.0..10.0 }
                    ?: throw ValidationException(field, "vote_average_min takes a score between 0 and 10")
                JsonPrimitive(score)
            }
        }
        return CriteriaRule(known.wireValue, known.op, value)
    }

    private fun ids(rule: CriteriaRule, field: String, valid: (Int) -> Boolean): JsonArray {
        val values = rule.ints()
        if (values.isEmpty() || values.size > MAX_VALUES || values.size != (rule.value as? JsonArray)?.size || !values.all(valid)) {
            throw ValidationException(field, "'${rule.field}' takes a list of 1 to $MAX_VALUES valid values")
        }
        return JsonArray(values.distinct().map(::JsonPrimitive))
    }

    private fun codes(rule: CriteriaRule, field: String, format: Regex, normalize: (String) -> String): JsonArray {
        val values = rule.strings()
        if (values.isEmpty() || values.size > MAX_VALUES || values.size != (rule.value as? JsonArray)?.size || !values.all(format::matches)) {
            throw ValidationException(field, "'${rule.field}' takes a list of 1 to $MAX_VALUES codes")
        }
        return JsonArray(values.map(normalize).distinct().map(::JsonPrimitive))
    }
}
