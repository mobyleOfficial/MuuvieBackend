package org.mobyle.domain.usecase.movies

import io.mockk.every
import io.mockk.mockk
import org.mobyle.domain.model.MaratonaFilter
import org.mobyle.domain.model.MaratonaSummary
import org.mobyle.domain.model.Page
import org.mobyle.domain.model.PageRequest
import org.mobyle.domain.repository.MaratonaRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GetMovieMaratonasTest {

    private val repository = mockk<MaratonaRepository>()
    private val maratonas = GetMovieMaratonas(repository)
    private val page = PageRequest(1, 20)

    @Test
    fun `should list the maratonas of the movie with the finished ones`() {
        val expected = Page.of(listOf(mockk<MaratonaSummary>(relaxed = true)), page, 1)
        every { repository.list(null, MaratonaFilter(movieId = 7, includeFinished = true), page) } returns expected

        assertEquals(expected, maratonas(null, 7, page))
    }

    @Test
    fun `should propagate a repository failure`() {
        every { repository.list(any(), any(), any()) } throws IllegalStateException("boom")

        assertFailsWith<IllegalStateException> { maratonas("ana", 7, page) }
    }
}
