package org.mobyle.data.remote.letterboxd

import kotlinx.coroutines.test.runTest
import org.jsoup.HttpStatusException
import org.jsoup.Jsoup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LetterboxdFilmDataSourceTest {

    private fun page(attributes: String) = Jsoup.parse("<html><body class=\"film\" $attributes></body></html>")

    @Test
    fun `reads the TMDB id of a film, never of a TV entry`() {
        assertEquals(666277, LetterboxdFilmDataSource.tmdbIdOf(page("data-tmdb-id=\"666277\" data-tmdb-type=\"movie\"")))
        assertNull(LetterboxdFilmDataSource.tmdbIdOf(page("data-tmdb-id=\"1399\" data-tmdb-type=\"tv\"")))
        assertNull(LetterboxdFilmDataSource.tmdbIdOf(page("")))
    }

    @Test
    fun `after three refusals in a row it stops asking`() = runTest {
        var calls = 0
        val source = LetterboxdFilmDataSource(fetch = { url ->
            calls++
            throw HttpStatusException("Forbidden", 403, url)
        })

        repeat(5) { assertNull(source.tmdbId("https://boxd.it/a$it")) }

        assertEquals(3, calls)
    }

    @Test
    fun `a page that answers resets the count`() = runTest {
        var calls = 0
        val source = LetterboxdFilmDataSource(fetch = { url ->
            calls++
            if (calls % 2 == 1) throw HttpStatusException("Too many", 429, url)
            Jsoup.parse("<body data-tmdb-id=\"7\" data-tmdb-type=\"movie\"></body>")
        })

        val ids = (1..6).map { source.tmdbId("https://boxd.it/a$it") }

        assertEquals(listOf(null, 7, null, 7, null, 7), ids)
    }
}
