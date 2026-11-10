package org.mobyle.data.repository

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.mobyle.data.local.movies.CachedData
import org.mobyle.data.local.movies.MovieCatalogDataSource
import org.mobyle.data.local.movies.PersonDataSource
import org.mobyle.data.remote.tmdb.TmdbDataSource
import org.mobyle.data.service.MovieEnrichmentService
import org.mobyle.domain.model.Movie
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MoviesRepositorySimilarTest {

    private val tmdbDataSource = mockk<TmdbDataSource>()
    private val catalog = mockk<MovieCatalogDataSource>(relaxed = true)
    private val repository = MoviesRepositoryImpl(
        tmdbDataSource = tmdbDataSource,
        movieCatalogDataSource = catalog,
        enrichmentService = mockk<MovieEnrichmentService>(relaxed = true),
        personDataSource = mockk<PersonDataSource>(relaxed = true)
    )
    private val alien = Movie(id = 2, tmdbId = 348, title = "Alien")

    @Test
    fun `an unknown movie answers null`() = runTest {
        every { catalog.findById(404) } returns null

        assertNull(repository.getSimilarMovies(404))
    }

    @Test
    fun `a placeholder without a TMDB id answers an empty list without calling TMDB`() = runTest {
        every { catalog.findById(1) } returns Movie(id = 1, tmdbId = null, title = "Só no Filmow")

        assertTrue(repository.getSimilarMovies(1)!!.isEmpty())
        coVerify(exactly = 0) { tmdbDataSource.getMovieDetail(any()) }
    }

    @Test
    fun `fresh cached similars are answered without calling TMDB`() = runTest {
        every { catalog.findById(1) } returns Movie(id = 1, tmdbId = 679, title = "Aliens")
        every { catalog.getSimilarMovies(679) } returns CachedData(listOf(alien), isStale = false)

        assertEquals(listOf(alien), repository.getSimilarMovies(1))
        coVerify(exactly = 0) { tmdbDataSource.getMovieDetail(any()) }
    }

    @Test
    fun `when TMDB fails and nothing is cached the list is empty`() = runTest {
        every { catalog.findById(1) } returns Movie(id = 1, tmdbId = 679, title = "Aliens")
        every { catalog.getSimilarMovies(679) } returns CachedData(emptyList(), isStale = true)
        coEvery { tmdbDataSource.getMovieDetail(679) } throws IllegalStateException("TMDB down")

        assertTrue(repository.getSimilarMovies(1)!!.isEmpty())
    }

    @Test
    fun `a movie TMDB lists no similars for is not asked again while the check is fresh`() = runTest {
        every { catalog.findById(1) } returns Movie(id = 1, tmdbId = 679, title = "Aliens")
        every { catalog.getSimilarMovies(679) } returns CachedData(emptyList(), isStale = false)

        assertTrue(repository.getSimilarMovies(1)!!.isEmpty())
        coVerify(exactly = 0) { tmdbDataSource.getMovieDetail(any()) }
    }
}
