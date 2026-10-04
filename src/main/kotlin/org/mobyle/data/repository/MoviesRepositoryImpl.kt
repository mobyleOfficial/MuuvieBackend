package org.mobyle.data.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.mobyle.data.local.movies.MovieCatalogDataSource
import org.mobyle.data.local.user.UserDatabaseDataSource
import org.mobyle.data.remote.tmdb.TmdbDataSource
import org.mobyle.data.remote.tmdb.toDomain
import org.mobyle.data.service.MovieEnrichmentService
import org.mobyle.domain.model.*
import org.mobyle.domain.repository.MoviesRepository
import org.mobyle.model.MovieListing
import org.mobyle.model.MovieListListing
import org.mobyle.model.MovieReviewListing
import org.slf4j.LoggerFactory

class MoviesRepositoryImpl(
    private val tmdbDataSource: TmdbDataSource,
    private val userDatabaseDataSource: UserDatabaseDataSource,
    private val movieCatalogDataSource: MovieCatalogDataSource,
    private val enrichmentService: MovieEnrichmentService
) : MoviesRepository {

    private val log = LoggerFactory.getLogger(MoviesRepositoryImpl::class.java)

    override suspend fun getTrendingMovies(page: Int): MovieListing {
        val listing = tmdbDataSource.getTrendingMovies(page).toDomain()
        cacheMovieListAsync(listing.movies)
        return listing
    }

    override suspend fun getMovieDetail(movieId: Int): MovieDetail {
        val local = movieCatalogDataSource.getLocalMovieDetail(movieId)
        if (local != null) {
            // Check if similars/providers need refresh
            val similarsCache = movieCatalogDataSource.getSimilarMovies(movieId)
            val providersCache = movieCatalogDataSource.getWatchProviders(movieId)

            if (similarsCache.isStale || providersCache.isStale) {
                refreshVolatileData(movieId)
            }

            return local.copy(
                similarMovies = similarsCache.data,
                watchProviders = providersCache.data
            )
        }

        return fetchAndCacheDetail(movieId)
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
            val detail = fetchAndCacheDetail(fetchTmdbId)
            val dbId = localMovie?.id ?: movieCatalogDataSource.findByTmdbId(fetchTmdbId)?.id ?: detail.id
            val overview = detail.overview.takeIf { it.isNotBlank() } ?: localMovie?.overview ?: ""
            return detail.copy(id = dbId, overview = overview)
        }

        // 4. Found in DB but no tmdbId — return scraped local data directly.
        // Background enrichment (MovieEnrichmentService) will resolve tmdbId over time;
        // once resolved, subsequent calls will hit step 2 above.
        if (localMovie != null) {
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
                    movieCatalogDataSource.saveSimilarMovies(tmdbId, detail.similarMovies)
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

        return detail.copy(id = dbId)
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
        cacheMovieListAsync(listing.movies)
        return listing
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
        cacheMovieListAsync(listing.movies)
        return listing
    }

    private fun cacheMovieListAsync(movies: List<Movie>) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                movieCatalogDataSource.cacheMovieListWithIds(movies)
            } catch (e: Exception) {
                log.warn("Failed to cache movie list: ${e.message}")
            }
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

    override suspend fun getMovieReviews(page: Int, userId: String?, movieId: Int?): MovieReviewListing {
        if (movieId != null) {
            return tmdbDataSource.getMovieReviews(movieId, page).toDomain()
        }
        return MovieReviewListing(totalPages = 0, totalResults = 0, reviews = emptyList())
    }

    override suspend fun getUserFavoriteMovies(userId: String, page: Int): MovieListing {
        return userDatabaseDataSource.getFavoriteMovies(userId, page)
    }

    override suspend fun getUserWatchList(userId: String, page: Int): MovieListing {
        return userDatabaseDataSource.getWatchlistMovies(userId, page)
    }

    override suspend fun getUserWatchedMovies(userId: String, page: Int): MovieListing {
        return userDatabaseDataSource.getWatchedMovies(userId, page)
    }

    override suspend fun getMovieLists(page: Int, userId: String?): MovieListListing {
        if (userId == null) {
            return MovieListListing(totalPages = 0, totalResults = 0, lists = emptyList())
        }
        return userDatabaseDataSource.getUserLists(userId, page)
    }

    override suspend fun getUserMovieLists(page: Int): MovieListListing {
        // Requires authenticated userId — use GET /movies/lists?userId=xxx instead
        return MovieListListing(totalPages = 0, totalResults = 0, lists = emptyList())
    }

    override suspend fun getMovieListDetail(listId: Int, page: Int): MovieListDetail {
        return userDatabaseDataSource.getListDetail(listId.toLong(), page)
    }

    override suspend fun getFeaturedLists(page: Int): MovieListListing {
        // No featured lists concept yet
        return MovieListListing(totalPages = 0, totalResults = 0, lists = emptyList())
    }

    override suspend fun getRecentMovies(userId: String, limit: Int): MovieListing {
        val movies = userDatabaseDataSource.getRecentWatchedMovies(userId, limit)
        return MovieListing(
            totalPages = 1,
            totalResults = movies.size,
            movies = movies
        )
    }
}
