package org.mobyle.domain.usecase.gamification

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlinx.serialization.Serializable
import org.mobyle.domain.model.ConflictException
import org.mobyle.domain.model.DiaryCategory
import org.mobyle.domain.model.DiaryDay
import org.mobyle.domain.model.DiaryDaySummary
import org.mobyle.domain.model.DiaryDraft
import org.mobyle.domain.model.DiaryMonthMoods
import org.mobyle.domain.model.DiaryMood
import org.mobyle.domain.model.DiaryTaskDraft
import org.mobyle.domain.model.DiaryYear
import org.mobyle.domain.model.Mission
import org.mobyle.domain.model.MissionType
import org.mobyle.domain.model.MissionWeek
import org.mobyle.domain.model.NotFoundException
import org.mobyle.domain.model.OwnedPet
import org.mobyle.domain.model.Pet
import org.mobyle.domain.model.PetDefinition
import org.mobyle.domain.model.PetRuleType
import org.mobyle.domain.model.PetShelter
import org.mobyle.domain.model.ValidationException
import org.mobyle.domain.model.WatchLog
import org.mobyle.domain.policy.ChallengeCriteriaMatcher
import org.mobyle.domain.policy.MissionCatalog
import org.mobyle.domain.policy.PetCatalog
import org.mobyle.domain.policy.XpCalculator
import org.mobyle.domain.repository.GamificationRepository
import org.mobyle.domain.repository.MaratonaCatalog
import org.mobyle.domain.repository.ShelfRepository
import org.mobyle.domain.usecase.feed.ActivityPublisher
import org.mobyle.domain.usecase.watch.today
import org.slf4j.LoggerFactory

private const val MAX_TASKS = 6
private const val TASK_MAX_LENGTH = 200
private const val NOTE_MAX_LENGTH = 5000

// ── Bichinho (F10.1) ────────────────────────────────────────────────────────

/** Puts the shelter together: the catalog with what the user owns and their progress. */
class PetShelterBuilder(private val repository: GamificationRepository) {
    fun shelter(userId: String, includeLocked: Boolean = true): PetShelter {
        val owned = repository.ownedPets(userId).associateBy { it.slug }
        val starterChosen = owned.values.any { it.starter }
        val progress = repository.petProgress(
            userId,
            PetCatalog.all.mapNotNull { it.rule.genreId }.toSet(),
            PetCatalog.all.mapNotNull { it.rule.beforeYear }.toSet()
        )
        val pets = PetCatalog.all
            .filter { definition ->
                val own = owned[definition.slug]
                when {
                    own != null -> true
                    !includeLocked -> false
                    // Until one is chosen the shelter is the starter pack; after, only the chosen one stays.
                    definition.rule.type == PetRuleType.STARTER -> !starterChosen
                    else -> starterChosen
                }
            }
            .map { definition -> petOf(definition, owned[definition.slug], progress.of(definition.rule)) }
        return PetShelter(starterChosen, pets, pets.firstOrNull { it.pinned })
    }

    private fun petOf(definition: PetDefinition, own: OwnedPet?, progress: Int): Pet {
        if (own == null) {
            return Pet(definition.slug, definition.rarity, definition.rule, unlocked = false, progress = progress.coerceAtMost(definition.rule.target))
        }
        val level = XpCalculator.level(own.xp)
        return Pet(
            slug = definition.slug,
            rarity = definition.rarity,
            rule = definition.rule,
            unlocked = true,
            progress = definition.rule.target,
            xp = own.xp,
            level = level,
            levelXp = XpCalculator.xpFor(level),
            nextLevelXp = if (level >= XpCalculator.MAX_LEVEL) XpCalculator.xpFor(level) else XpCalculator.xpFor(level + 1),
            starter = own.starter,
            pinned = own.pinned,
            unlockedAt = own.unlockedAt
        )
    }
}

/** Unlocks every bichinho whose rule the user reached, once a starter exists. */
class PetUnlocker(private val repository: GamificationRepository, private val activities: ActivityPublisher) {
    fun check(userId: String): List<String> {
        val owned = repository.ownedPets(userId)
        if (owned.none { it.starter }) return emptyList()
        val have = owned.map { it.slug }.toSet()
        val candidates = PetCatalog.all.filter { it.rule.type != PetRuleType.STARTER && it.slug !in have }
        if (candidates.isEmpty()) return emptyList()
        val progress = repository.petProgress(
            userId,
            candidates.mapNotNull { it.rule.genreId }.toSet(),
            candidates.mapNotNull { it.rule.beforeYear }.toSet()
        )
        return candidates
            .filter { progress.of(it.rule) >= it.rule.target }
            .mapNotNull { definition ->
                repository.addPet(userId, definition.slug, starter = false, pinned = false)?.also {
                    activities.petUnlocked(userId, it.id, definition)
                }?.slug
            }
    }
}

class GetPetShelter(private val shelter: PetShelterBuilder) {
    operator fun invoke(userId: String): PetShelter = shelter.shelter(userId)

    /** Someone's bichinhos as the profile shows them: only the unlocked ones. */
    fun public(userId: String): PetShelter = shelter.shelter(userId, includeLocked = false)
}

/** The starter pack (F10.1): one of the three, for good. */
class ChooseStarterPet(
    private val repository: GamificationRepository,
    private val unlocker: PetUnlocker,
    private val shelter: PetShelterBuilder,
    private val activities: ActivityPublisher
) {
    operator fun invoke(userId: String, slug: String): PetShelter {
        val definition = PetCatalog.starters.firstOrNull { it.slug == slug }
            ?: throw ValidationException("slug", "Pick one of the starter bichinhos")
        if (repository.ownedPets(userId).any { it.starter }) {
            throw ConflictException("starter_chosen", "The starter bichinho was already chosen")
        }
        val pet = repository.addPet(userId, definition.slug, starter = true, pinned = true)
            ?: throw ConflictException("starter_chosen", "The starter bichinho was already chosen")
        activities.petUnlocked(userId, pet.id, definition)
        // Who already watched a lot gets the bichinhos they earned right away.
        unlocker.check(userId)
        return shelter.shelter(userId)
    }
}

class PinPet(private val repository: GamificationRepository, private val shelter: PetShelterBuilder) {
    operator fun invoke(userId: String, slug: String): PetShelter {
        if (!repository.pin(userId, slug)) throw NotFoundException("Bichinho not found")
        return shelter.shelter(userId)
    }
}

// ── Missions (F10.2) ────────────────────────────────────────────────────────

/**
 * The missions of the week with the user's progress. A mission that reached its target is
 * recorded and gives its XP once, whether it was noticed here or when a session was logged.
 */
class WeeklyMissions(
    private val repository: GamificationRepository,
    private val catalog: MaratonaCatalog,
    private val today: () -> LocalDate = ::today
) {
    operator fun invoke(userId: String): MissionWeek {
        val now = today()
        val start = MissionCatalog.weekStart(now)
        val end = MissionCatalog.weekEnd(now)
        val definitions = MissionCatalog.of(start)
        val week = repository.weekActivity(userId, start, end)
        val needsFacts = definitions.any { it.type == MissionType.CRITERIA_MOVIES }
        val facts = if (needsFacts) week.finishedMovieIds.mapNotNull { catalog.facts(it) } else emptyList()
        val done = repository.completedMissions(userId, start).toMutableMap()
        val missions = definitions.map { definition ->
            val progress = when (definition.type) {
                MissionType.SESSIONS -> week.sessions
                MissionType.MOVIES -> week.finishedMovieIds.size
                MissionType.CRITERIA_MOVIES -> facts.count { definition.criteria?.let { c -> ChallengeCriteriaMatcher.matches(c, it) } == true }
                MissionType.EMOTIONS -> week.sessionsWithEmotions
                MissionType.CINECLUBE -> week.cineclubesJoined
                MissionType.CHALLENGE -> week.challengesCompleted
            }
            if (progress >= definition.target && definition.slug !in done &&
                repository.completeMission(userId, start, definition.slug, definition.xp)
            ) {
                repository.addXp(userId, definition.xp)
                done[definition.slug] = kotlinx.datetime.Clock.System.now()
            }
            Mission(
                slug = definition.slug,
                type = definition.type,
                target = definition.target,
                progress = progress.coerceAtMost(definition.target),
                xp = definition.xp,
                completed = definition.slug in done,
                completedAt = done[definition.slug]
            )
        }
        return MissionWeek(start, end, missions)
    }
}

/**
 * After a session is logged (B10): XP for the pinned bichinho, the week's missions and new
 * unlocks. Imports never come through here. A failure is logged and never fails the session.
 */
class GamificationHook(
    private val repository: GamificationRepository,
    private val missions: WeeklyMissions,
    private val unlocker: PetUnlocker
) {
    private val log = LoggerFactory.getLogger(GamificationHook::class.java)

    fun sessionLogged(userId: String, watchLog: WatchLog) {
        try {
            repository.addXp(userId, XpCalculator.forSession(watchLog))
            missions(userId)
            unlocker.check(userId)
        } catch (e: Exception) {
            log.error("[GAMIFICATION] Could not count a session", e)
        }
    }
}

// ── Caderninho (F10.3) ──────────────────────────────────────────────────────

@Serializable
data class DiaryTaskInput(
    val category: String = "other",
    val description: String = "",
    val done: Boolean = false,
    val movieId: Long? = null
)

@Serializable
data class DiaryDayInput(
    val mood: String? = null,
    val note: String? = null,
    val tasks: List<DiaryTaskInput> = emptyList()
)

private fun checkDay(date: LocalDate, today: LocalDate) {
    // A day ahead is fine, for users east of UTC.
    if (date > today.plus(DatePeriod(days = 1))) throw ValidationException("date", "The day cannot be in the future")
}

/**
 * The private diary. Reading a day never creates it (the Maratona does, on purpose we do
 * not); saving creates it, and saving it empty deletes it.
 */
class GetDiaryDay(private val repository: GamificationRepository, private val today: () -> LocalDate = ::today) {
    operator fun invoke(userId: String, date: LocalDate): DiaryDay {
        checkDay(date, today())
        return repository.diaryDay(userId, date) ?: DiaryDay(date, null, null, emptyList(), saved = false)
    }
}

class SaveDiaryDay(
    private val repository: GamificationRepository,
    private val shelves: ShelfRepository,
    private val today: () -> LocalDate = ::today
) {
    operator fun invoke(userId: String, date: LocalDate, input: DiaryDayInput): DiaryDay {
        checkDay(date, today())
        val mood = input.mood?.let { DiaryMood.fromWireValue(it) ?: throw ValidationException("mood", "Unknown mood $it") }
        val note = input.note?.trim()?.takeIf { it.isNotEmpty() }
        if (note != null && note.length > NOTE_MAX_LENGTH) {
            throw ValidationException("note", "The note has at most $NOTE_MAX_LENGTH characters")
        }
        if (input.tasks.size > MAX_TASKS) throw ValidationException("tasks", "A day has at most $MAX_TASKS goals")
        val tasks = input.tasks.map { task ->
            val description = task.description.trim()
            if (description.isEmpty() || description.length > TASK_MAX_LENGTH) {
                throw ValidationException("tasks", "A goal has between 1 and $TASK_MAX_LENGTH characters")
            }
            val category = DiaryCategory.fromWireValue(task.category)
                ?: throw ValidationException("tasks", "Unknown category ${task.category}")
            val movieId = task.movieId?.let { shelves.resolveMovieId(it, null, null) ?: throw NotFoundException("Movie not found") }
            DiaryTaskDraft(category, description, task.done, movieId)
        }
        val draft = DiaryDraft(mood, note, tasks)
        if (draft.empty) repository.deleteDiaryDay(userId, date) else repository.saveDiaryDay(userId, date, draft)
        return repository.diaryDay(userId, date) ?: DiaryDay(date, null, null, emptyList(), saved = false)
    }
}

/** The days written in a month, for the calendar of the Caderninho. */
class GetDiaryMonth(private val repository: GamificationRepository) {
    operator fun invoke(userId: String, year: Int, month: Int): List<DiaryDaySummary> {
        if (month !in 1..12) throw ValidationException("month", "Invalid month")
        val first = LocalDate(year, month, 1)
        val last = first.plus(DatePeriod(months = 1)).plus(DatePeriod(days = -1))
        return repository.diaryDays(userId, first, last)
    }
}

/** The year of moods (F10.3). */
class GetDiaryYear(private val repository: GamificationRepository) {
    operator fun invoke(userId: String, year: Int): DiaryYear {
        val moods = repository.diaryMoods(userId, LocalDate(year, 1, 1), LocalDate(year, 12, 31))
        val months = (1..12).map { month ->
            DiaryMonthMoods(
                month,
                moods.filter { it.first.monthNumber == month && it.second != null }
                    .groupingBy { it.second!!.wireValue }
                    .eachCount()
            )
        }
        val top = moods.mapNotNull { it.second }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        return DiaryYear(year, moods.size, months, top)
    }
}
