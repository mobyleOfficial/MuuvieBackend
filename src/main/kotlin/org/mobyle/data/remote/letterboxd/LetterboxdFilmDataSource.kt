package org.mobyle.data.remote.letterboxd

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.HttpStatusException
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.mobyle.domain.repository.LetterboxdFilmLookup
import org.slf4j.LoggerFactory
import java.io.IOException

/**
 * Reads the TMDB id of a Letterboxd film from its page: the export only links films by
 * `boxd.it` short links, which redirect to `letterboxd.com/film/<slug>/`, whose `<body>` carries
 * `data-tmdb-id` and `data-tmdb-type` (`movie` or `tv`).
 *
 * After [MAX_BLOCKED] refusals (403/429) in a row the source is considered blocked and every
 * later lookup answers null at once, so a blocked import falls back to the title search instead
 * of waiting on each movie.
 */
class LetterboxdFilmDataSource(
    private val fetch: (url: String) -> Document = ::fetchPage
) : LetterboxdFilmLookup {
    private val log = LoggerFactory.getLogger(LetterboxdFilmDataSource::class.java)

    @Volatile private var refusalsInARow = 0

    override suspend fun tmdbId(filmUri: String): Int? {
        if (refusalsInARow >= MAX_BLOCKED) return null
        return try {
            val page = withContext(Dispatchers.IO) { fetch(filmUri) }
            refusalsInARow = 0
            tmdbIdOf(page)
        } catch (e: HttpStatusException) {
            if (e.statusCode == FORBIDDEN || e.statusCode == TOO_MANY_REQUESTS) {
                refusalsInARow++
                if (refusalsInARow == MAX_BLOCKED) log.warn("[LETTERBOXD] Blocked (${e.statusCode}); falling back to the title search")
            }
            null
        } catch (e: IOException) {
            log.warn("[LETTERBOXD] Could not read $filmUri: ${e.message}")
            null
        }
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
        private const val TIMEOUT_MS = 15_000
        private const val FORBIDDEN = 403
        private const val TOO_MANY_REQUESTS = 429
        private const val MAX_BLOCKED = 3

        /** A film's TMDB id; null for a TV entry or a page without one. */
        fun tmdbIdOf(page: Document): Int? {
            val body = page.body()
            if (body.attr("data-tmdb-type").let { it.isNotEmpty() && it != "movie" }) return null
            return body.attr("data-tmdb-id").toIntOrNull()?.takeIf { it > 0 }
        }

        private fun fetchPage(url: String): Document =
            Jsoup.connect(url).userAgent(USER_AGENT).timeout(TIMEOUT_MS).followRedirects(true).get()
    }
}
