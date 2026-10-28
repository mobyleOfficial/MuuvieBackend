package org.mobyle.domain.usecase.movies

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.datetime.LocalDate
import org.mobyle.domain.model.MonthActivity
import org.mobyle.domain.model.MovieActivity
import org.mobyle.domain.model.ValidationException
import org.mobyle.domain.repository.WatchRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GetMovieTimelineTest {

    private val repository = mockk<WatchRepository>()
    private val timeline = GetMovieTimeline(repository) { LocalDate(2026, 2, 14) }

    @Test
    fun `should sum the days per month, oldest first, with every month of the window`() {
        every { repository.activity(7, LocalDate(2025, 12, 1), LocalDate(2026, 3, 1)) } returns MovieActivity(
            sessionsByDay = mapOf(LocalDate(2025, 12, 3) to 2, LocalDate(2025, 12, 20) to 1, LocalDate(2026, 2, 1) to 4),
            ratingsByDay = mapOf(LocalDate(2026, 2, 13) to 1)
        )

        val result = timeline(7, 3)

        assertEquals(
            listOf(
                MonthActivity("2025-12", sessions = 3, ratings = 0),
                MonthActivity("2026-01", sessions = 0, ratings = 0),
                MonthActivity("2026-02", sessions = 4, ratings = 1)
            ),
            result.months
        )
    }

    @Test
    fun `should default to 12 months ending in the current one`() {
        every { repository.activity(any(), any(), any()) } returns MovieActivity()

        val result = timeline(7)

        assertEquals(12, result.months.size)
        assertEquals("2025-03", result.months.first().month)
        assertEquals("2026-02", result.months.last().month)
        verify { repository.activity(7, LocalDate(2025, 3, 1), LocalDate(2026, 3, 1)) }
    }

    @Test
    fun `should reject a window outside 1 to 24 months`() {
        assertFailsWith<ValidationException> { timeline(7, 0) }
        assertFailsWith<ValidationException> { timeline(7, 25) }
    }

    @Test
    fun `should propagate a repository failure`() {
        every { repository.activity(any(), any(), any()) } throws IllegalStateException("boom")

        assertFailsWith<IllegalStateException> { timeline(7, 1) }
    }
}
