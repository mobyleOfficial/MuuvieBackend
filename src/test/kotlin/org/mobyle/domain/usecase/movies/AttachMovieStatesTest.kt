package org.mobyle.domain.usecase.movies

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.mobyle.domain.model.Movie
import org.mobyle.domain.model.MovieListState
import org.mobyle.domain.model.vocabulary.Rating
import org.mobyle.domain.model.vocabulary.WatchStatus
import org.mobyle.domain.repository.WatchRepository
import org.mobyle.model.MovieListing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AttachMovieStatesTest {

    private val repository = mockk<WatchRepository>()
    private val attach = AttachMovieStates(repository)
    private val movies = listOf(Movie(id = 1, title = "A"), Movie(id = 2, title = "B"))

    @Test
    fun `should add the viewer state to the movies that have one, in one query`() {
        val loved = MovieListState(status = WatchStatus.WATCHED, rating = Rating.LOVED)
        every { repository.listStates("ana", listOf(1, 2)) } returns mapOf(1L to loved)

        val result = attach("ana", MovieListing(totalPages = 1, totalResults = 2, movies = movies))

        assertEquals(loved, result.movies[0].me)
        assertNull(result.movies[1].me)
        verify(exactly = 1) { repository.listStates(any(), any()) }
    }

    @Test
    fun `should leave the movies untouched when logged out`() {
        assertEquals(movies, attach(null, movies))
        verify(exactly = 0) { repository.listStates(any(), any()) }
    }
}
