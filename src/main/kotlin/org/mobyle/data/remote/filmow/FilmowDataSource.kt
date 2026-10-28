package org.mobyle.data.remote.filmow

import org.mobyle.domain.model.FilmowProfile

interface FilmowDataSource {
    /**
     * [knownFilmowIds]: movies the catalog resolves without their Filmow detail page; the
     * scraper skips those pages unless it needs the user's watched date.
     */
    suspend fun scrapeProfile(cookies: String, username: String, knownFilmowIds: Set<String> = emptySet()): FilmowProfile
}
