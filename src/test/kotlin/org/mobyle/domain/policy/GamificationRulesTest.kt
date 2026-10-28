package org.mobyle.domain.policy

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import org.mobyle.domain.model.WatchLog
import org.mobyle.domain.model.vocabulary.Visibility
import org.mobyle.domain.model.vocabulary.WatchMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GamificationRulesTest {

    private fun log(minutes: Int?, completed: Boolean) = WatchLog(
        id = 1, userId = "ana", movieId = 10, watchedOn = LocalDate(2026, 10, 1), cycle = 1, mode = WatchMode.WATCHED,
        minutesWatched = minutes, percent = null, completed = completed, venue = null, providerName = null,
        emotions = emptyList(), gif = null, comment = null, hasSpoiler = false, visibility = Visibility.PUBLIC,
        createdAt = Instant.fromEpochSeconds(0), updatedAt = Instant.fromEpochSeconds(0)
    )

    @Test
    fun `a finished session gives more XP and minutes are capped`() {
        assertEquals(20 + 9, XpCalculator.forSession(log(95, completed = true)))
        assertEquals(4, XpCalculator.forSession(log(40, completed = false)))
        assertEquals(20 + 30, XpCalculator.forSession(log(600, completed = true)))
        assertEquals(20, XpCalculator.forSession(log(null, completed = true)))
    }

    @Test
    fun `levels grow with the XP`() {
        assertEquals(1, XpCalculator.level(0))
        assertEquals(1, XpCalculator.level(99))
        assertEquals(2, XpCalculator.level(100))
        assertEquals(3, XpCalculator.level(300))
        assertEquals(XpCalculator.MAX_LEVEL, XpCalculator.level(10_000_000))
    }

    @Test
    fun `every week has three distinct missions from Monday`() {
        var monday = LocalDate(2026, 1, 5)
        repeat(60) {
            val missions = MissionCatalog.of(monday)
            assertEquals(MissionCatalog.PER_WEEK, missions.toSet().size)
            monday = monday.plus(DatePeriod(days = 7))
        }
        val wednesday = LocalDate(2026, 10, 28)
        assertEquals(DayOfWeek.MONDAY, MissionCatalog.weekStart(wednesday).dayOfWeek)
        assertEquals(LocalDate(2026, 11, 1), MissionCatalog.weekEnd(wednesday))
        assertEquals(MissionCatalog.of(LocalDate(2026, 10, 26)), MissionCatalog.of(MissionCatalog.weekStart(wednesday)))
    }

    @Test
    fun `every mission criteria is valid and the pool rotates`() {
        MissionCatalog.pool.mapNotNull { it.criteria }.forEach { ChallengeCriteriaMatcher.validate(it, "criteria") }
        val seen = (0 until 30).flatMap { MissionCatalog.of(LocalDate(2026, 1, 5).plus(DatePeriod(days = 7 * it))) }.toSet()
        assertTrue(seen.size >= MissionCatalog.pool.size - 2)
        assertEquals(3, PetCatalog.starters.size)
        assertEquals(PetCatalog.all.size, PetCatalog.all.map { it.slug }.toSet().size)
    }
}
