package org.mobyle.data.repository

import kotlinx.datetime.LocalDate
import org.mobyle.domain.model.FilmowList
import org.mobyle.domain.model.FilmowMoviePartial
import org.mobyle.domain.model.FilmowProfile
import org.mobyle.domain.model.Movie
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FilmowImportReaderTest {

    private val casablanca = Movie(title = "Casablanca", filmowId = "10", releaseDate = "1942-11-26", userRating = 4.5)

    @Test
    fun `maps the profile to the bundle, with the date of recently watched and the detail page data`() {
        val profile = FilmowProfile(
            username = "ana",
            displayName = "Ana",
            watched = listOf(casablanca),
            recentlyWatched = listOf(casablanca.copy(watchedAt = "2023-11-04T00:00:00Z")),
            watchlist = listOf(Movie(title = "Vertigo", filmowId = "11"), Movie(title = "Vertigo", filmowId = "11")),
            favorites = listOf(casablanca),
            lists = listOf(FilmowList("L1", "Noir", "desc", "https://filmow.com/l/1", "https://cover", listOf(casablanca))),
            filmowDetails = mapOf("10" to FilmowMoviePartial(imdbUrl = "https://imdb.com/title/tt0034583/", runtime = 102))
        )

        val bundle = FilmowImportReader.toBundle(profile)

        val watched = bundle.watched.single()
        assertEquals("10", watched.externalId)
        assertEquals(1942, watched.year)
        assertEquals(4.5, watched.stars)
        assertEquals(LocalDate(2023, 11, 4), watched.watchedOn)
        assertEquals(102, watched.runtime)
        assertEquals("https://imdb.com/title/tt0034583/", watched.imdbUrl)
        assertEquals(1, bundle.watchlist.size)
        assertEquals("L1", bundle.lists.single().externalId)
        assertEquals("https://cover", bundle.lists.single().coverUrl)
        assertEquals(1, bundle.preview().logs)
    }

    @Test
    fun `a movie without a Filmow id is identified by title and year`() {
        val bundle = FilmowImportReader.toBundle(
            FilmowProfile(username = "ana", displayName = "Ana", watched = listOf(Movie(title = "Amélie", releaseDate = "2001")))
        )

        assertEquals("title:amelie:2001", bundle.watched.single().externalId)
        assertNull(bundle.watched.single().watchedOn)
    }
}
