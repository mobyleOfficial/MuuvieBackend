package org.mobyle.data.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mobyle.data.local.movies.MovieCatalogDataSource
import org.mobyle.data.local.movies.PersonDataSource
import org.mobyle.data.remote.tmdb.TmdbDataSource
import org.mobyle.data.remote.tmdb.toDomain
import org.mobyle.data.service.MovieEnrichmentService
import org.mobyle.domain.model.*
import org.mobyle.domain.repository.MoviesRepository
import org.mobyle.model.MovieListing
import org.slf4j.LoggerFactory

class MoviesRepositoryImpl(
    private val tmdbDataSource: TmdbDataSource,
    private val movieCatalogDataSource: MovieCatalogDataSource,
    private val enrichmentService: MovieEnrichmentService,
    private val personDataSource: PersonDataSource
) : MoviesRepository {

    private val log = LoggerFactory.getLogger(MoviesRepositoryImpl::class.java)

    override suspend fun getTrendingMovies(page: Int): MovieListing {
        val listing = tmdbDataSource.getTrendingMovies(page).toDomain()
        return listing.copy(movies = cacheAndAssignIds(listing.movies))
    }

    override suspend fun getMovieDetail(movieId: Int): MovieDetail {
        val local = movieCatalogDataSource.getLocalMovieDetail(movieId)
        val detail = if (local != null) {
            val similarsCache = movieCatalogDataSource.getSimilarMovies(movieId)
            val providersCache = movieCatalogDataSource.getWatchProviders(movieId)
            if (similarsCache.isStale || providersCache.isStale) refreshVolatileData(movieId)
            local.copy(similarMovies = similarsCache.data, watchProviders = providersCache.data)
        } else {
            try {
                fetchAndCacheDetail(movieId)
            } catch (e: Exception) {
                log.warn("TMDB fetch failed for tmdbId=$movieId: ${e.message}")
                return MovieDetail(title = "", overview = "")
            }
        }
        return detail
    }

    override suspend fun lookupMovieDetail(id: Long?, tmdbId: Int?, filmowId: String?): MovieDetail? {
        // 1. Search DB by any provided identifier (id has highest priority)
        val localMovie = when {
            id != null -> movieCatalogDataSource.findById(id)
            tmdbId != null -> movieCatalogDataSource.findByTmdbId(tmdbId)
            filmowId != null -> movieCatalogDataSource.findByFilmowId(filmowId)
            else -> null
        }

        // 2. Found in DB with tmdbId — return local enriched detail
        if (localMovie?.tmdbId != null) {
            val local = movieCatalogDataSource.getLocalMovieDetail(localMovie.tmdbId)
            if (local != null) return local.copy(id = localMovie.id)
        }

        // 3. Not enriched locally — fetch from TMDB
        val fetchTmdbId = tmdbId ?: localMovie?.tmdbId
        if (fetchTmdbId != null) {
            val detail = try {
                fetchAndCacheDetail(fetchTmdbId)
            } catch (e: Exception) {
                log.warn("TMDB fetch failed for tmdbId=$fetchTmdbId: ${e.message}")
                null
            }
            if (detail != null) {
                val dbId = localMovie?.id ?: movieCatalogDataSource.findByTmdbId(fetchTmdbId)?.id ?: detail.id
                val overview = detail.overview.takeIf { it.isNotBlank() } ?: localMovie?.overview ?: ""
                return detail.copy(id = dbId, overview = overview)
            }
        }

        // 4. Found in DB — try title search on TMDB as fallback
        if (localMovie != null) {
            val titleMatch = try {
                val title = localMovie.title
                if (title.isNotBlank()) {
                    val searchResult = tmdbDataSource.searchMovies(title, page = 1)
                    val match = searchResult.results.firstOrNull { it.title.equals(title, ignoreCase = true) }
                        ?: searchResult.results.firstOrNull()
                    if (match != null) {
                        val detail = fetchAndCacheDetail(match.id)
                        movieCatalogDataSource.resolveScrapedMovie(
                            oldDbId = localMovie.id,
                            realTmdbId = match.id,
                            filmowId = localMovie.filmowId
                        )
                        detail.copy(id = localMovie.id)
                    } else null
                } else null
            } catch (e: Exception) {
                log.warn("TMDB title search fallback failed for '${localMovie.title}': ${e.message}")
                null
            }

            if (titleMatch != null) {
                return titleMatch
            }

            // 5. No TMDB match — return scraped local data directly.
            return movieCatalogDataSource.getLocalMovieDetailByDbId(localMovie.id)
        }

        return null
    }

    private fun resolveFilmowPlaceholder(filmowId: String, currentTmdbId: Int?, realTmdbId: Int) {
        if (currentTmdbId == realTmdbId) return
        val dbId = movieCatalogDataSource.getDbIdByFilmowId(filmowId) ?: return
        movieCatalogDataSource.resolveScrapedMovie(oldDbId = dbId, realTmdbId = realTmdbId, filmowId = filmowId)
    }

    private suspend fun fetchAndCacheDetail(tmdbId: Int): MovieDetail {
        val response = tmdbDataSource.getMovieDetail(tmdbId)
        val detail = response.toDomain()

        // Save similar movies synchronously so they get DB ids in this response
        val similarsWithIds = cacheAndAssignIds(detail.similarMovies)

        val dbId = try {
            val id = movieCatalogDataSource.upsertMovie(
                Movie(
                    tmdbId = tmdbId, title = detail.title, originalTitle = detail.originalTitle,
                    overview = detail.overview,
                    posterPath = detail.posterPath, backdropPath = detail.backdropPath,
                    voteAverage = detail.voteAverage, releaseDate = detail.releaseDate
                )
            )
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    movieCatalogDataSource.enrichMovie(tmdbId, detail, response.credits)
                    movieCatalogDataSource.saveSimilarMovies(tmdbId, similarsWithIds)
                    movieCatalogDataSource.saveWatchProviders(tmdbId, detail.watchProviders)
                } catch (e: Exception) {
                    log.warn("Failed to enrich movie detail $tmdbId: ${e.message}")
                }
            }
            id
        } catch (e: Exception) {
            log.warn("Failed to cache movie detail $tmdbId: ${e.message}")
            0L
        }

        return detail.copy(id = dbId, similarMovies = similarsWithIds)
    }

    private fun refreshVolatileData(tmdbId: Int) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val response = tmdbDataSource.getMovieDetail(tmdbId)
                val detail = response.toDomain()
                movieCatalogDataSource.saveSimilarMovies(tmdbId, detail.similarMovies)
                movieCatalogDataSource.saveWatchProviders(tmdbId, detail.watchProviders)
            } catch (e: Exception) {
                log.warn("Failed to refresh volatile data for $tmdbId: ${e.message}")
            }
        }
    }

    override suspend fun searchMovies(query: String, page: Int): MovieListing {
        val listing = tmdbDataSource.searchMovies(query, page).toDomain()
        return listing.copy(movies = cacheAndAssignIds(listing.movies))
    }

    override suspend fun discoverMovies(
        page: Int,
        year: Int?,
        releaseDateGte: String?,
        releaseDateLte: String?,
        sortBy: String?,
        genres: String?,
        language: String?,
        country: String?,
        voteCountGte: Int?
    ): MovieListing {
        val listing = tmdbDataSource.discoverMovies(
            page, year, releaseDateGte, releaseDateLte, sortBy, genres, language, country, voteCountGte
        ).toDomain()
        return listing.copy(movies = cacheAndAssignIds(listing.movies))
    }

    private suspend fun cacheAndAssignIds(movies: List<Movie>): List<Movie> {
        return try {
            withContext(Dispatchers.IO) {
                movieCatalogDataSource.cacheMovieListWithIds(movies)
            }
        } catch (e: Exception) {
            log.warn("Failed to cache movie list: ${e.message}")
            movies
        }
    }

    override suspend fun getGenres(): List<Genre> {
        return tmdbDataSource.getGenres().genres.map { it.toDomain() }
    }

    override suspend fun getCountries(): List<Country> {
        return tmdbDataSource.getCountries().map { it.toDomain() }
    }

    override suspend fun getLanguages(): List<Language> {
        return tmdbDataSource.getLanguages().map { it.toDomain() }
    }

    override suspend fun getPersonDetail(personId: Long): PersonDetail? {
        return withContext(Dispatchers.IO) {
            val person = personDataSource.getPersonById(personId) ?: return@withContext null
            val credits = personDataSource.getCreditsForPerson(personId)
            PersonDetail(person = person, credits = credits)
        }
    }

    override suspend fun getPersonDetailByTmdbId(tmdbId: Int): PersonDetail? {
        val personId = withContext(Dispatchers.IO) { personDataSource.findPersonIdByTmdbId(tmdbId) } ?: return null
        return getPersonDetail(personId)
    }
}
