package org.mobyle.domain.usecase.maratonas

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlinx.serialization.Serializable
import org.mobyle.domain.model.CategoryDraft
import org.mobyle.domain.model.ChallengeCriteria
import org.mobyle.domain.model.ChallengeDraft
import org.mobyle.domain.model.LevelDraft
import org.mobyle.domain.model.MaratonaDraft
import org.mobyle.domain.model.PrizeDraft
import org.mobyle.domain.model.TeamDraft
import org.mobyle.domain.model.ValidationException
import org.mobyle.domain.model.vocabulary.ChallengeType
import org.mobyle.domain.policy.ChallengeCriteriaMatcher

private val SLUG_FORMAT = Regex("^[a-z0-9][a-z0-9-]{1,48}[a-z0-9]$")
private val COLOR_FORMAT = Regex("^#[0-9A-Fa-f]{6}$")
private val URL_FORMAT = Regex("^https?://\\S+$")

private const val TITLE_MAX_LENGTH = 100
private const val DESCRIPTION_MAX_LENGTH = 2000
private const val URL_MAX_LENGTH = 500
private const val MAX_DAYS = 366
private const val MAX_CATEGORIES = 20
private const val CATEGORY_NAME_MAX_LENGTH = 80
private const val MAX_CHALLENGES_PER_CATEGORY = 50
const val MIN_CHALLENGES = 3
private const val MAX_CHALLENGES = 200
private const val CHALLENGE_MAX_LENGTH = 300
private const val MAX_LEVELS = 10
private const val LEVEL_NAME_MAX_LENGTH = 50
private const val MAX_TEAMS = 20
private const val TEAM_NAME_MAX_LENGTH = 50
private const val MAX_PRIZES = 20
private const val PRIZE_TITLE_MAX_LENGTH = 100
private const val PRIZE_DESCRIPTION_MAX_LENGTH = 500
private const val PASSWORD_MIN_LENGTH = 4
private const val PASSWORD_MAX_LENGTH = 72 // BCrypt limit
const val DEFAULT_LEVEL_NAME = "Maratonista"

/**
 * Body of POST /maratonas and PUT /maratonas/{slug} (F7.7). Items with an id update the
 * stored ones on edit. A challenge with criteria is checked automatically; without, it is
 * only marked by hand. Without levels, one level asks for every challenge.
 */
@Serializable
data class MaratonaInput(
    val slug: String = "",
    val title: String = "",
    val description: String? = null,
    val logoUrl: String? = null,
    val accentColor: String? = null,
    val startsOn: String = "",
    val endsOn: String = "",
    // Null keeps the current password on edit; removePassword takes it away.
    val password: String? = null,
    val removePassword: Boolean = false,
    val socialLink: String? = null,
    val categories: List<CategoryInput> = emptyList(),
    val levels: List<LevelInput> = emptyList(),
    val teams: List<TeamInput> = emptyList(),
    val prizes: List<PrizeInput> = emptyList()
)

@Serializable
data class CategoryInput(
    val id: Long? = null,
    val name: String = "",
    val teamOnly: Boolean = false,
    val challenges: List<ChallengeInput> = emptyList()
)

@Serializable
data class ChallengeInput(
    val id: Long? = null,
    val description: String = "",
    val criteria: ChallengeCriteria? = null,
    val daily: Boolean = false
)

@Serializable
data class LevelInput(val id: Long? = null, val name: String = "", val minChallenges: Int = 0)

@Serializable
data class TeamInput(val id: Long? = null, val name: String = "", val color: String? = null)

@Serializable
data class PrizeInput(val title: String = "", val description: String? = null)

fun normalizedSlug(value: String): String? = value.trim().lowercase().takeIf { SLUG_FORMAT.matches(it) }

/** Validates a maratona. A new one cannot end in the past ([today] is the server's date). */
fun maratonaDraftOf(input: MaratonaInput, templateId: String?, today: LocalDate, isNew: Boolean): MaratonaDraft {
    val slug = normalizedSlug(input.slug)
        ?: throw ValidationException("slug", "Slug must have 3 to 50 lowercase letters, digits or hyphens")
    val title = required(input.title, "title", TITLE_MAX_LENGTH)
    val startsOn = date(input.startsOn, "startsOn")
    val endsOn = date(input.endsOn, "endsOn")
    if (endsOn < startsOn) throw ValidationException("endsOn", "The maratona must end on or after its start")
    if (startsOn.plus(MAX_DAYS, DateTimeUnit.DAY) < endsOn) {
        throw ValidationException("endsOn", "A maratona lasts at most $MAX_DAYS days")
    }
    if (isNew && endsOn < today) throw ValidationException("endsOn", "The maratona cannot end in the past")

    if (input.categories.isEmpty() || input.categories.size > MAX_CATEGORIES) {
        throw ValidationException("categories", "A maratona has between 1 and $MAX_CATEGORIES categories")
    }
    val categories = input.categories.map { category ->
        if (category.challenges.isEmpty() || category.challenges.size > MAX_CHALLENGES_PER_CATEGORY) {
            throw ValidationException("categories", "Each category has between 1 and $MAX_CHALLENGES_PER_CATEGORY challenges")
        }
        CategoryDraft(
            id = category.id,
            name = required(category.name, "categories", CATEGORY_NAME_MAX_LENGTH),
            teamOnly = category.teamOnly,
            challenges = category.challenges.map { challenge ->
                val criteria = challenge.criteria?.let { ChallengeCriteriaMatcher.validate(it, "challenges") }
                ChallengeDraft(
                    id = challenge.id,
                    description = required(challenge.description, "challenges", CHALLENGE_MAX_LENGTH),
                    type = if (criteria != null) ChallengeType.CRITERIA else ChallengeType.FREE,
                    criteria = criteria,
                    daily = challenge.daily
                )
            }
        )
    }
    val totalChallenges = categories.sumOf { it.challenges.size }
    if (totalChallenges !in MIN_CHALLENGES..MAX_CHALLENGES) {
        throw ValidationException("challenges", "A maratona has between $MIN_CHALLENGES and $MAX_CHALLENGES challenges")
    }

    if (input.levels.size > MAX_LEVELS) throw ValidationException("levels", "A maratona has at most $MAX_LEVELS levels")
    val levels = input.levels.map {
        if (it.minChallenges !in 1..totalChallenges) {
            throw ValidationException("levels", "A level asks for 1 to $totalChallenges challenges")
        }
        LevelDraft(it.id, required(it.name, "levels", LEVEL_NAME_MAX_LENGTH), it.minChallenges)
    }.ifEmpty { listOf(LevelDraft(null, DEFAULT_LEVEL_NAME, totalChallenges)) }

    if (input.teams.size > MAX_TEAMS) throw ValidationException("teams", "A maratona has at most $MAX_TEAMS teams")
    val teams = input.teams.map { TeamDraft(it.id, required(it.name, "teams", TEAM_NAME_MAX_LENGTH), color(it.color, "teams")) }

    if (input.prizes.size > MAX_PRIZES) throw ValidationException("prizes", "A maratona has at most $MAX_PRIZES prizes")
    val prizes = input.prizes.map {
        PrizeDraft(required(it.title, "prizes", PRIZE_TITLE_MAX_LENGTH), optional(it.description, "prizes", PRIZE_DESCRIPTION_MAX_LENGTH))
    }

    return MaratonaDraft(
        slug = slug,
        title = title,
        description = optional(input.description, "description", DESCRIPTION_MAX_LENGTH),
        logoUrl = url(input.logoUrl, "logoUrl"),
        accentColor = color(input.accentColor, "accentColor"),
        startsOn = startsOn,
        endsOn = endsOn,
        socialLink = url(input.socialLink, "socialLink"),
        templateId = templateId,
        categories = categories,
        levels = levels,
        teams = teams,
        prizes = prizes
    )
}

/** A new password, or null when none was sent. */
fun maratonaPassword(value: String?): String? {
    if (value == null) return null
    if (value.length !in PASSWORD_MIN_LENGTH..PASSWORD_MAX_LENGTH) {
        throw ValidationException("password", "Password must have between $PASSWORD_MIN_LENGTH and $PASSWORD_MAX_LENGTH characters")
    }
    return value
}

internal fun required(value: String, field: String, max: Int): String {
    val trimmed = value.trim()
    if (trimmed.isEmpty() || trimmed.length > max) throw ValidationException(field, "$field must have between 1 and $max characters")
    return trimmed
}

internal fun optional(value: String?, field: String, max: Int): String? {
    val trimmed = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (trimmed.length > max) throw ValidationException(field, "$field must have at most $max characters")
    return trimmed
}

internal fun url(value: String?, field: String): String? {
    val trimmed = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (trimmed.length > URL_MAX_LENGTH || !URL_FORMAT.matches(trimmed)) {
        throw ValidationException(field, "$field must be an http(s) URL")
    }
    return trimmed
}

internal fun color(value: String?, field: String): String? {
    val trimmed = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (!COLOR_FORMAT.matches(trimmed)) throw ValidationException(field, "Color must be #RRGGBB")
    return trimmed.uppercase()
}

internal fun date(value: String, field: String): LocalDate =
    runCatching { LocalDate.parse(value.trim()) }.getOrElse { throw ValidationException(field, "$field must be YYYY-MM-DD") }
