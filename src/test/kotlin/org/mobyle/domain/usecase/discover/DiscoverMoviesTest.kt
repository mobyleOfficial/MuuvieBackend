package org.mobyle.domain.usecase.discover

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.datetime.LocalDate
import org.mobyle.domain.model.Page
import org.mobyle.domain.model.PageRequest
import org.mobyle.domain.model.ValidationException
import org.mobyle.domain.repository.DiscoverRepository
import kotlin.test.Test
import kotlin.test.assertFailsWith

class DiscoverMoviesTest {

    private val repository = mockk<DiscoverRepository>()
    private val page = PageRequest(1, 20)

    @Test
    fun `trending should count the last days including today`() {
        every { repository.trendingMovies(any(), page) } returns Page.of(emptyList(), page, 0)

        DiscoverTrendingMovies(repository) { LocalDate(2026, 10, 27) }(7, page)

        verify { repository.trendingMovies(LocalDate(2026, 10, 21), page) }
    }

    @Test
    fun `trending should refuse a window outside 1 to 30 days`() {
        val trending = DiscoverTrendingMovies(repository) { LocalDate(2026, 10, 27) }

        assertFailsWith<ValidationException> { trending(0, page) }
        assertFailsWith<ValidationException> { trending(31, page) }
    }

    @Test
    fun `loved should ask for at least two labels`() {
        every { repository.lovedMovies(2, page) } returns Page.of(emptyList(), page, 0)

        DiscoverLovedMovies(repository)(page)

        verify { repository.lovedMovies(2, page) }
    }
}
