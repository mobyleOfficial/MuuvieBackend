package org.mobyle.domain.repository

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import org.mobyle.domain.model.DiaryDay
import org.mobyle.domain.model.DiaryDaySummary
import org.mobyle.domain.model.DiaryDraft
import org.mobyle.domain.model.DiaryMood
import org.mobyle.domain.model.OwnedPet
import org.mobyle.domain.model.PetProgress
import org.mobyle.domain.model.WeekActivity

/** Bichinhos, missions and the Caderninho (B10). Users are identified by their external id. */
interface GamificationRepository {
    fun ownedPets(userId: String): List<OwnedPet>

    /** Adds the bichinho; null when the user already had it. */
    fun addPet(userId: String, slug: String, starter: Boolean, pinned: Boolean): OwnedPet?

    /** Makes [slug] the pinned bichinho; false when the user does not have it. */
    fun pin(userId: String, slug: String): Boolean

    /** Gives [xp] to the pinned bichinho; none when there is no bichinho yet. */
    fun addXp(userId: String, xp: Int)

    /** Counts for the unlock rules from the user's own sessions (imports left out). */
    fun petProgress(userId: String, genreIds: Set<Int>, beforeYears: Set<Int>): PetProgress

    fun weekActivity(userId: String, from: LocalDate, to: LocalDate): WeekActivity

    fun completedMissions(userId: String, weekStart: LocalDate): Map<String, Instant>

    /** Records the mission; false when it was already done. */
    fun completeMission(userId: String, weekStart: LocalDate, slug: String, xp: Int): Boolean

    fun diaryDay(userId: String, date: LocalDate): DiaryDay?

    fun saveDiaryDay(userId: String, date: LocalDate, draft: DiaryDraft)

    fun deleteDiaryDay(userId: String, date: LocalDate)

    fun diaryDays(userId: String, from: LocalDate, to: LocalDate): List<DiaryDaySummary>

    fun diaryMoods(userId: String, from: LocalDate, to: LocalDate): List<Pair<LocalDate, DiaryMood?>>
}
