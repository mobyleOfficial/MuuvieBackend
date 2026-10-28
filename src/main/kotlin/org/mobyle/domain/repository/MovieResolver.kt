package org.mobyle.domain.repository

import org.mobyle.domain.model.ImportedMovie
import org.mobyle.domain.model.MovieResolution

/**
 * Finds the TMDB movie of a movie known only by its source data, in a cascade: IMDb link,
 * exact title and year, then title confirmed by the director (filmow-movie-enrichment).
 */
interface MovieResolver {
    suspend fun resolve(movie: ImportedMovie): MovieResolution
}
