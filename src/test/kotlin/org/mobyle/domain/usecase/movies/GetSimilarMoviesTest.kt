package org.mobyle.domain.usecase.movies

import io.mockk.coEvery
import io.mockk.mockk
import org.mobyle.domain.model.Movie
import org.mobyle.domain.model.NotFoundException
import org.mobyle.domain.repository.MoviesRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GetSimilarMoviesTest {

    private val repository = mockk<MoviesRepository>()
    private val getSimilarMovies = GetSimilarMovies(repository)

    @Test
    fun `answers the similar movies with the id that was asked for`() {
        val similarMovies = listOf(Movie(id = 2, tmdbId = 20, title = "Alien"), Movie(id = 3, tmdbId = 30, title = "Aliens"))
        coEvery { repository.getSimilarMovies(1) } returns similarMovies

        val result = getSimilarMovies(1)

        assertEquals(1L, result.movieId)
        assertEquals(similarMovies, result.results)
    }

    @Test
    fun `a movie without similar movies answers an empty list`() {
        coEvery { repository.getSimilarMovies(1) } returns emptyList()

        assertTrue(getSimilarMovies(1).results.isEmpty())
    }

    @Test
    fun `an unknown movie is not found`() {
        coEvery { repository.getSimilarMovies(404) } returns null

        assertFailsWith<NotFoundException> { getSimilarMovies(404) }
    }
}
