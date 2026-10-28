package org.mobyle.domain.usecase.movies

import io.mockk.every
import io.mockk.mockk
import kotlinx.datetime.LocalDate
import org.mobyle.domain.model.CineClubeFilter
import org.mobyle.domain.model.CineClubeSummary
import org.mobyle.domain.model.Page
import org.mobyle.domain.model.PageRequest
import org.mobyle.domain.repository.CineClubeRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GetMovieCineClubesTest {

    private val repository = mockk<CineClubeRepository>()
    private val today = LocalDate(2026, 10, 27)
    private val cineclubes = GetMovieCineClubes(repository) { today }
    private val page = PageRequest(2, 10)

    @Test
    fun `should list the CineClubes of the movie with the finished ones`() {
        val expected = Page.of(listOf(mockk<CineClubeSummary>(relaxed = true)), page, 11)
        every { repository.list("ana", CineClubeFilter(movieId = 7, includeFinished = true), page, today) } returns expected

        assertEquals(expected, cineclubes("ana", 7, page))
    }

    @Test
    fun `should propagate a repository failure`() {
        every { repository.list(any(), any(), any(), any()) } throws IllegalStateException("boom")

        assertFailsWith<IllegalStateException> { cineclubes(null, 7, page) }
    }
}
