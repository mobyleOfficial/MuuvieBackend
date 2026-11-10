package org.mobyle.domain.usecase.movies

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.runs
import io.mockk.verify
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

    private val repository = mockk<MoviesRepository>().also {
        every { it.enrichAhead(any()) } just runs
    }
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
    fun `a title the app cannot read is swapped for the readable one, and readable ones are not looked up`() {
        val underYourBed = Movie(id = 4, tmdbId = 40, title = "アンダー・ユア・ベッド")
        coEvery { repository.getSimilarMovies(1) } returns listOf(alien, underYourBed)
        coEvery { repository.getMovieTraits(any()) } returns emptyMap()
        coEvery { repository.readableTitles(listOf(4L)) } returns mapOf(4L to "Under Your Bed")

        val result = getSimilarMovies(1)

        assertEquals(listOf("Alien", "Under Your Bed"), result.results.map { movie -> movie.title })
        coVerify(exactly = 1) { repository.readableTitles(listOf(4L)) }
    }

    @Test
    fun `without a readable title anywhere the original stays`() {
        val underYourBed = Movie(id = 4, tmdbId = 40, title = "アンダー・ユア・ベッド")
        coEvery { repository.getSimilarMovies(1) } returns listOf(underYourBed)
        coEvery { repository.getMovieTraits(any()) } returns emptyMap()
        coEvery { repository.readableTitles(any()) } returns emptyMap()

        assertEquals("アンダー・ユア・ベッド", getSimilarMovies(1).results.single().title)
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

    @Test
    fun `movies with no saved traits are enriched ahead, the others are not`() {
        coEvery { repository.getSimilarMovies(1) } returns listOf(alien, aliens)
        coEvery { repository.getMovieTraits(listOf(2L, 3L, 1L)) } returns mapOf(
            1L to MovieTraits(genres = setOf("Terror")),
            3L to MovieTraits(genres = setOf("Terror"))
        )

        getSimilarMovies(1)

        verify(exactly = 1) { repository.enrichAhead(listOf(2L)) }
    }
}
