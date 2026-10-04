package org.mobyle.domain.usecase.filmow

import org.mobyle.data.local.movies.MovieCatalogDataSource
import org.mobyle.data.local.user.UserDatabaseDataSource
import org.mobyle.domain.model.FilmowProfile
import org.slf4j.LoggerFactory

class ImportFilmowData(
    private val userDatabaseDataSource: UserDatabaseDataSource,
    private val movieCatalogDataSource: MovieCatalogDataSource
) {
    private val log = LoggerFactory.getLogger(ImportFilmowData::class.java)

    operator fun invoke(userExternalId: String, profile: FilmowProfile): FilmowProfile {
        log.info("[IMPORT] Starting — ${profile.watched.size} watched, " +
            "${profile.watchlist.size} watchlist, ${profile.favorites.size} favorites, " +
            "${profile.lists.size} lists")

        val updatedWatched = if (profile.watched.isNotEmpty()) {
            log.info("[IMPORT] Importing ${profile.watched.size} watched movies...")
            userDatabaseDataSource.importMovies(
                userExternalId = userExternalId,
                movies = profile.watched,
                status = "watched"
            ).also { log.info("[IMPORT] Watched done.") }
        } else emptyList()

        val updatedWatchlist = if (profile.watchlist.isNotEmpty()) {
            log.info("[IMPORT] Importing ${profile.watchlist.size} watchlist movies...")
            userDatabaseDataSource.importMovies(
                userExternalId = userExternalId,
                movies = profile.watchlist,
                status = "want_to_watch"
            ).also { log.info("[IMPORT] Watchlist done.") }
        } else emptyList()

        val updatedFavorites = if (profile.favorites.isNotEmpty()) {
            log.info("[IMPORT] Importing ${profile.favorites.size} favorite movies...")
            userDatabaseDataSource.importMovies(
                userExternalId = userExternalId,
                movies = profile.favorites,
                status = "watched",
                isFavorite = true
            ).also { log.info("[IMPORT] Favorites done.") }
        } else emptyList()

        val updatedLists = if (profile.lists.isNotEmpty()) {
            log.info("[IMPORT] Importing ${profile.lists.size} lists...")
            userDatabaseDataSource.importLists(
                userExternalId = userExternalId,
                lists = profile.lists
            ).also { log.info("[IMPORT] Lists done.") }
        } else emptyList()

        val updatedRecentlyWatched = if (profile.recentlyWatched.isNotEmpty()) {
            log.info("[IMPORT] Importing ${profile.recentlyWatched.size} recently watched movies...")
            userDatabaseDataSource.importRecentlyWatched(
                userExternalId = userExternalId,
                movies = profile.recentlyWatched
            ).also { log.info("[IMPORT] Recently watched done.") }
        } else emptyList()

        if (profile.filmowDetails.isNotEmpty()) {
            log.info("[IMPORT] Saving Filmow detail data for ${profile.filmowDetails.size} movies...")
            movieCatalogDataSource.saveFilmowPartials(profile.filmowDetails)
            log.info("[IMPORT] Filmow detail data done.")
        }

        log.info("[IMPORT] All done for user $userExternalId")
        return profile.copy(
            watched = updatedWatched,
            watchlist = updatedWatchlist,
            favorites = updatedFavorites,
            recentlyWatched = updatedRecentlyWatched,
            lists = updatedLists
        )
    }
}
