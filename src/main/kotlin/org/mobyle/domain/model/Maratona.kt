package org.mobyle.domain.model

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import org.mobyle.domain.model.vocabulary.ChallengeLinkSource
import org.mobyle.domain.model.vocabulary.ChallengeType
import org.mobyle.domain.model.vocabulary.MaratonaStatus

/**
 * A maratona (B7): an event with a period, categories of challenges, levels, teams and prizes.
 * [me] is the viewer's participation; [canEdit] is true for the owner and the co-owners.
 */
@Serializable
data class Maratona(
    val id: Long,
    val slug: String,
    val title: String,
    val description: String?,
    val logoUrl: String?,
    val accentColor: String?,
    val startsOn: LocalDate,
    val endsOn: LocalDate,
    val status: MaratonaStatus,
    val hasPassword: Boolean,
    val socialLink: String?,
    val trusted: Boolean,
    val templateId: String?,
    val owner: UserSummary,
    val coOwners: List<UserSummary>,
    val categories: List<MaratonaCategory>,
    val levels: List<MaratonaLevel>,
    val teams: List<MaratonaTeam>,
    val prizes: List<MaratonaPrize>,
    val totalChallenges: Int,
    val totalParticipants: Int,
    val createdAt: Instant,
    val me: MyParticipation? = null,
    val canEdit: Boolean = false
) {
    fun isOwner(userId: String) = owner.id == userId

    fun isEditor(userId: String) = isOwner(userId) || coOwners.any { it.id == userId }

    val challenges: List<MaratonaChallenge> get() = categories.flatMap { it.challenges }
}

/** A row of the maratona list (F7.2). */
@Serializable
data class MaratonaSummary(
    val id: Long,
    val slug: String,
    val title: String,
    val logoUrl: String?,
    val accentColor: String?,
    val startsOn: LocalDate,
    val endsOn: LocalDate,
    val status: MaratonaStatus,
    val hasPassword: Boolean,
    val trusted: Boolean,
    val owner: UserSummary,
    val totalChallenges: Int,
    val totalParticipants: Int,
    val joined: Boolean
)

@Serializable
data class MaratonaCategory(
    val id: Long,
    val name: String,
    val position: Int,
    val teamOnly: Boolean,
    val challenges: List<MaratonaChallenge>
)

/** A challenge; [completed] and [links] are the viewer's, filled only on the challenges endpoint. */
@Serializable
data class MaratonaChallenge(
    val id: Long,
    val categoryId: Long,
    val description: String,
    val type: ChallengeType,
    val criteria: ChallengeCriteria?,
    val position: Int,
    val daily: Boolean,
    val completed: Boolean = false,
    val links: List<ChallengeLink> = emptyList()
)

/** A movie tied to a challenge by a participant. */
@Serializable
data class ChallengeLink(
    val challengeId: Long,
    val movieId: Long,
    val tmdbId: Int?,
    val title: String,
    val posterPath: String?,
    val source: ChallengeLinkSource,
    val completedAt: Instant
)

/** Reaching [minChallenges] done challenges completes the maratona at this level. */
@Serializable
data class MaratonaLevel(val id: Long, val name: String, val minChallenges: Int)

@Serializable
data class MaratonaTeam(val id: Long, val name: String, val color: String?)

@Serializable
data class MaratonaPrize(val id: Long, val title: String, val description: String?)

/** The viewer's participation, as the detail shows it. */
@Serializable
data class MyParticipation(
    val levelId: Long?,
    val teamId: Long?,
    val queueShelfId: Long?,
    val completed: Int,
    val joinedAt: Instant
)

/** A participation as the use cases need it. */
data class Participation(
    val id: Long,
    val maratonaId: Long,
    val userId: String,
    val levelId: Long?,
    val teamId: Long?,
    val queueShelfId: Long?,
    val joinedAt: Instant
)

/** A row of the participant ranking (F7.6): more challenges first, then who got there earlier. */
@Serializable
data class RankingEntry(
    val position: Int,
    val user: UserSummary,
    val teamId: Long?,
    val completed: Int,
    val lastCompletedAt: Instant?
)

@Serializable
data class TeamRankingEntry(
    val position: Int,
    val team: MaratonaTeam,
    val members: Int,
    val completed: Int,
    val lastCompletedAt: Instant?
)

/** A participant who reached a level when the maratona ended. */
data class Finisher(val userId: String, val level: MaratonaLevel, val completed: Int)

/**
 * Query of the maratona list (F7.2): upcoming and running ones, plus finished ones with
 * [includeFinished]. [open] keeps the ones without a password; [mine] lists the ones the
 * viewer owns or co-owns, and [participant] the ones a user joined, both in any status
 * but cancelled ones only for [mine]. [movieId] keeps the ones where the movie completed a
 * challenge for someone (the movie detail, B13.5).
 */
data class MaratonaFilter(
    val query: String? = null,
    val open: Boolean = false,
    val includeFinished: Boolean = false,
    val trustedOnly: Boolean = false,
    val mine: Boolean = false,
    val participant: String? = null,
    val movieId: Long? = null
)

/** Fields of a maratona, already validated. Items with an id update the stored one. */
data class MaratonaDraft(
    val slug: String,
    val title: String,
    val description: String?,
    val logoUrl: String?,
    val accentColor: String?,
    val startsOn: LocalDate,
    val endsOn: LocalDate,
    val socialLink: String?,
    val templateId: String?,
    val categories: List<CategoryDraft>,
    val levels: List<LevelDraft>,
    val teams: List<TeamDraft>,
    val prizes: List<PrizeDraft>
)

data class CategoryDraft(val id: Long?, val name: String, val teamOnly: Boolean, val challenges: List<ChallengeDraft>)

data class ChallengeDraft(
    val id: Long?,
    val description: String,
    val type: ChallengeType,
    val criteria: ChallengeCriteria?,
    val daily: Boolean
)

data class LevelDraft(val id: Long?, val name: String, val minChallenges: Int)

data class TeamDraft(val id: Long?, val name: String, val color: String?)

data class PrizeDraft(val title: String, val description: String?)

/** What a password change does on save: keep the stored one, set a new hash, or remove it. */
sealed interface PasswordChange {
    data object Keep : PasswordChange
    data object Remove : PasswordChange
    data class Set(val hash: String) : PasswordChange
}
