package org.mobyle.data.repository

import org.mobyle.data.local.cineclubes.CineClubeDataSource
import org.mobyle.domain.model.WatchProvider
import org.mobyle.domain.repository.CineClubeCatalog
import org.mobyle.domain.repository.CineClubeRepository
import org.mobyle.domain.repository.MoviesRepository

class CineClubeRepositoryImpl(cineclubeDataSource: CineClubeDataSource) : CineClubeRepository by cineclubeDataSource

/** Watch providers through the movie detail, which keeps them cached and refreshes them weekly. */
class CineClubeCatalogImpl(private val movies: MoviesRepository) : CineClubeCatalog {
    override suspend fun watchProviders(tmdbId: Int): List<WatchProvider> = movies.getMovieDetail(tmdbId).watchProviders
}
