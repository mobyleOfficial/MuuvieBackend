package org.mobyle.domain.usecase.movies

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.mobyle.domain.model.Movie
import org.mobyle.domain.model.MovieTraits
import org.mobyle.domain.model.NotFoundException
import org.mobyle.domain.repository.MoviesRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GetSimilarMoviesTest {

    private val repository = mockk<MoviesRepository>()
    private val getSimilarMovies = GetSimilarMovies(repository)
    private val alien = Movie(id = 2, tmdbId = 20, title = "Alien")
    private val aliens = Movie(id = 3, tmdbId = 30, title = "Aliens")

    @Test
    fun `answers the similar movies with the id that was asked for`() {
        coEvery { repository.getSimilarMovies(1) } returns listOf(alien, aliens)
        coEvery { repository.getMovieTraits(any()) } returns emptyMap()

        val result = getSimilarMovies(1)

        assertEquals(1L, result.movieId)
        assertEquals(listOf(alien, aliens), result.results)
        assertEquals(listOf(2L, 3L), result.affinities.map { affinity -> affinity.movieId })
    }

    @Test
    fun `the movies are sorted by score with the shared traits that explain it`() {
        coEvery { repository.getSimilarMovies(1) } returns listOf(alien, aliens)
        coEvery { repository.getMovieTraits(listOf(2L, 3L, 1L)) } returns mapOf(
            1L to MovieTraits(genres = setOf("Ação", "Ficção científica"), keywords = setOf("alien", "space marine")),
            2L to MovieTraits(genres = setOf("Terror"), keywords = setOf("haunted house")),
            3L to MovieTraits(genres = setOf("Ação", "Ficção científica"), keywords = setOf("alien", "space marine"))
        )

        val result = getSimilarMovies(1)

        assertEquals(listOf(aliens, alien), result.results)
        assertEquals(listOf(3L, 2L), result.affinities.map { affinity -> affinity.movieId })
        assertEquals(listOf("alien", "space marine"), result.affinities.first().sharedKeywords)
    }

    @Test
    fun `a movie without similar movies answers an empty list without reading traits`() {
        coEvery { repository.getSimilarMovies(1) } returns emptyList()

        val result = getSimilarMovies(1)

        assertTrue(result.results.isEmpty())
        assertTrue(result.affinities.isEmpty())
        coVerify(exactly = 0) { repository.getMovieTraits(any()) }
    }

    @Test
    fun `an unknown movie is not found`() {
        coEvery { repository.getSimilarMovies(404) } returns null

        assertFailsWith<NotFoundException> { getSimilarMovies(404) }
    }
}
