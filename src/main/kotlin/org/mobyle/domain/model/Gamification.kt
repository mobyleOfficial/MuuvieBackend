package org.mobyle.domain.model

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ── Bichinho (B10.1) ────────────────────────────────────────────────────────

@Serializable
enum class PetRarity(val wireValue: String) {
    @SerialName("starter") STARTER("starter"),
    @SerialName("common") COMMON("common"),
    @SerialName("rare") RARE("rare"),
    @SerialName("legendary") LEGENDARY("legendary")
}

/** What unlocks a bichinho; [target] is how many of it. Imported sessions never count. */
@Serializable
enum class PetRuleType(val wireValue: String) {
    // Chosen in the starter pack.
    @SerialName("starter") STARTER("starter"),
    @SerialName("movies") MOVIES("movies"),
    @SerialName("minutes") MINUTES("minutes"),
    // Movies of the genre [PetRule.genreId].
    @SerialName("genre_movies") GENRE_MOVIES("genre_movies"),
    // Movies released before [PetRule.beforeYear].
    @SerialName("old_movies") OLD_MOVIES("old_movies"),
    @SerialName("maratonas_finished") MARATONAS_FINISHED("maratonas_finished"),
    @SerialName("cineclubes_joined") CINECLUBES_JOINED("cineclubes_joined"),
    @SerialName("missions") MISSIONS("missions")
}

@Serializable
data class PetRule(val type: PetRuleType, val target: Int, val genreId: Int? = null, val beforeYear: Int? = null)

/** A bichinho of the catalog. The app draws it and names it by [slug]. */
data class PetDefinition(val slug: String, val rarity: PetRarity, val rule: PetRule)

/**
 * A bichinho as the user sees it in the shelter. Locked ones carry their [progress] toward
 * the rule; unlocked ones their XP and level.
 */
@Serializable
data class Pet(
    val slug: String,
    val rarity: PetRarity,
    val rule: PetRule,
    val unlocked: Boolean,
    val progress: Int,
    val xp: Int = 0,
    val level: Int = 0,
    val levelXp: Int = 0,
    val nextLevelXp: Int = 0,
    val starter: Boolean = false,
    val pinned: Boolean = false,
    val unlockedAt: Instant? = null
)

/** The shelter ("abrigo", F10.1): the starter pack until one is chosen, then every bichinho. */
@Serializable
data class PetShelter(val starterChosen: Boolean, val pets: List<Pet>, val pinned: Pet?)

/** A row of user_pets. */
data class OwnedPet(val id: Long, val slug: String, val xp: Int, val starter: Boolean, val pinned: Boolean, val unlockedAt: Instant)

/** What the unlock rules read, counted from the user's own (not imported) sessions. */
data class PetProgress(
    val movies: Int,
    val minutes: Int,
    val genreMovies: Map<Int, Int>,
    val oldMovies: Map<Int, Int>,
    val maratonasFinished: Int,
    val cineclubesJoined: Int,
    val missions: Int
) {
    fun of(rule: PetRule): Int = when (rule.type) {
        PetRuleType.STARTER -> 0
        PetRuleType.MOVIES -> movies
        PetRuleType.MINUTES -> minutes
        PetRuleType.GENRE_MOVIES -> genreMovies[rule.genreId] ?: 0
        PetRuleType.OLD_MOVIES -> oldMovies[rule.beforeYear] ?: 0
        PetRuleType.MARATONAS_FINISHED -> maratonasFinished
        PetRuleType.CINECLUBES_JOINED -> cineclubesJoined
        PetRuleType.MISSIONS -> missions
    }
}

// ── Missions (B10.2) ────────────────────────────────────────────────────────

@Serializable
enum class MissionType(val wireValue: String) {
    @SerialName("sessions") SESSIONS("sessions"),
    @SerialName("movies") MOVIES("movies"),
    // Finished movies that match [MissionDefinition.criteria].
    @SerialName("criteria_movies") CRITERIA_MOVIES("criteria_movies"),
    @SerialName("emotions") EMOTIONS("emotions"),
    @SerialName("cineclube") CINECLUBE("cineclube"),
    @SerialName("challenge") CHALLENGE("challenge")
}

data class MissionDefinition(
    val slug: String,
    val type: MissionType,
    val target: Int,
    val xp: Int,
    val criteria: ChallengeCriteria? = null
)

/** A mission of the week with the user's progress (F10.2). */
@Serializable
data class Mission(
    val slug: String,
    val type: MissionType,
    val target: Int,
    val progress: Int,
    val xp: Int,
    val completed: Boolean,
    val completedAt: Instant? = null
)

@Serializable
data class MissionWeek(val weekStart: LocalDate, val weekEnd: LocalDate, val missions: List<Mission>)

/** What a week of missions reads: the user's own sessions and actions of the week. */
data class WeekActivity(
    val sessions: Int,
    val sessionsWithEmotions: Int,
    val finishedMovieIds: List<Long>,
    val cineclubesJoined: Int,
    val challengesCompleted: Int
)

// ── Caderninho (B10.3) ──────────────────────────────────────────────────────

@Serializable
enum class DiaryMood(val wireValue: String) {
    @SerialName("great") GREAT("great"),
    @SerialName("good") GOOD("good"),
    @SerialName("okay") OKAY("okay"),
    @SerialName("bad") BAD("bad"),
    @SerialName("awful") AWFUL("awful");

    companion object {
        fun fromWireValue(value: String): DiaryMood? = entries.firstOrNull { it.wireValue == value }
    }
}

@Serializable
enum class DiaryCategory(val wireValue: String) {
    @SerialName("movie") MOVIE("movie"),
    @SerialName("short") SHORT("short"),
    @SerialName("series") SERIES("series"),
    @SerialName("study") STUDY("study"),
    @SerialName("other") OTHER("other");

    companion object {
        fun fromWireValue(value: String): DiaryCategory? = entries.firstOrNull { it.wireValue == value }
    }
}

/** A day of the Caderninho. [saved] is false for a day that was never written. */
@Serializable
data class DiaryDay(
    val date: LocalDate,
    val mood: DiaryMood?,
    val note: String?,
    val tasks: List<DiaryTask>,
    val saved: Boolean
)

@Serializable
data class DiaryTask(
    val id: Long,
    val category: DiaryCategory,
    val description: String,
    val done: Boolean,
    val position: Int,
    val movie: MovieCard?
)

/** A day of the month view: the mood and how many tasks are done. */
@Serializable
data class DiaryDaySummary(val date: LocalDate, val mood: DiaryMood?, val tasks: Int, val done: Int, val hasNote: Boolean)

@Serializable
data class DiaryMonthMoods(val month: Int, val moods: Map<String, Int>)

/** The year of moods (F10.3). */
@Serializable
data class DiaryYear(val year: Int, val days: Int, val months: List<DiaryMonthMoods>, val topMood: DiaryMood?)

/** Fields of a day to save, already validated. */
data class DiaryDraft(val mood: DiaryMood?, val note: String?, val tasks: List<DiaryTaskDraft>) {
    val empty: Boolean get() = mood == null && note == null && tasks.isEmpty()
}

data class DiaryTaskDraft(val category: DiaryCategory, val description: String, val done: Boolean, val movieId: Long?)
