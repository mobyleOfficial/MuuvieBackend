package org.mobyle.data.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mobyle.data.local.movies.MovieCatalogDataSource
import org.mobyle.data.local.movies.MovieLikesDataSource
import org.mobyle.data.local.movies.PersonDataSource
import org.mobyle.data.local.movies.ReviewLikesDataSource
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
    private val enrichmentService: MovieEnrichmentService,
    private val reviewLikesDataSource: ReviewLikesDataSource,
    private val movieLikesDataSource: MovieLikesDataSource,
    private val personDataSource: PersonDataSource
) : MoviesRepository {

    private val log = LoggerFactory.getLogger(MoviesRepositoryImpl::class.java)

    override suspend fun getTrendingMovies(page: Int): MovieListing {
        val listing = tmdbDataSource.getTrendingMovies(page).toDomain()
        return listing.copy(movies = cacheAndAssignIds(listing.movies))
    }

    override suspend fun getMovieDetail(movieId: Int, userId: String?): MovieDetail {
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
        return enrichWithLikes(detail, userId)
    }

    override suspend fun lookupMovieDetail(id: Long?, tmdbId: Int?, filmowId: String?, userId: String?): MovieDetail? {
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
            if (local != null) return enrichWithLikes(local.copy(id = localMovie.id), userId)
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
                return enrichWithLikes(detail.copy(id = dbId, overview = overview), userId)
            }
        }

        // 4. Found in DB but no tmdbId — return scraped local data directly.
        if (localMovie != null) {
            return movieCatalogDataSource.getLocalMovieDetailByDbId(localMovie.id)
                ?.let { enrichWithLikes(it, userId) }
        }

        return null
    }

    private suspend fun enrichWithLikes(detail: MovieDetail, userId: String?): MovieDetail {
        if (detail.id <= 0L) return detail
        val likeCount = withContext(Dispatchers.IO) { movieLikesDataSource.getLikeCount(detail.id) }
        val likedByMe = if (userId != null) {
            withContext(Dispatchers.IO) { movieLikesDataSource.isLikedByUser(userId, detail.id) }
        } else false
        val userMovieStatus = if (userId != null) {
            withContext(Dispatchers.IO) { userDatabaseDataSource.getUserMovieStatus(userId, detail.id) }
        } else null
        return detail.copy(
            likeCount = likeCount,
            likedByMe = likedByMe,
            userRating = userMovieStatus?.rating,
            watchStatus = userMovieStatus?.status
        )
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

    override suspend fun getMovieReviews(page: Int, userId: String?, movieId: Int?): MovieReviewListing {
        if (movieId != null) {
            val listing = tmdbDataSource.getMovieReviews(movieId, page).toDomain()
            val reviewIds = listing.reviews.map { it.id }
            val likeCounts = withContext(Dispatchers.IO) {
                reviewLikesDataSource.getLikeCounts(reviewIds)
            }
            val likedByUser = if (userId != null) {
                withContext(Dispatchers.IO) {
                    reviewLikesDataSource.getLikedReviewIds(userId, reviewIds)
                }
            } else emptySet()
            val enriched = listing.reviews.map { review ->
                review.copy(
                    likeCount = likeCounts[review.id] ?: 0,
                    likedByMe = review.id in likedByUser
                )
            }
            return listing.copy(reviews = enriched)
        }
        return MovieReviewListing(totalPages = 0, totalResults = 0, reviews = emptyList())
    }

    override suspend fun likeReview(userId: String, reviewId: String) {
        withContext(Dispatchers.IO) {
            reviewLikesDataSource.like(userId, reviewId)
        }
    }

    override suspend fun unlikeReview(userId: String, reviewId: String) {
        withContext(Dispatchers.IO) {
            reviewLikesDataSource.unlike(userId, reviewId)
        }
    }

    override suspend fun likeMovie(userId: String, movieId: Long) {
        withContext(Dispatchers.IO) {
            movieLikesDataSource.like(userId, movieId)
        }
    }

    override suspend fun unlikeMovie(userId: String, movieId: Long) {
        withContext(Dispatchers.IO) {
            movieLikesDataSource.unlike(userId, movieId)
        }
    }

    override suspend fun getPersonDetail(personId: Long): PersonDetail? {
        return withContext(Dispatchers.IO) {
            val person = personDataSource.getPersonById(personId) ?: return@withContext null
            val credits = personDataSource.getCreditsForPerson(personId)
            PersonDetail(person = person, credits = credits)
        }
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

    override suspend fun createMovieList(userId: String, name: String, description: String?, movieIds: List<Long>): MovieList {
        return withContext(Dispatchers.IO) {
            userDatabaseDataSource.createList(userId, name, description, movieIds)
        }
    }

    override suspend fun deleteMovieList(userId: String, listId: Int) {
        withContext(Dispatchers.IO) {
            userDatabaseDataSource.deleteList(userId, listId.toLong())
        }
    }

    override suspend fun addMovieToList(userId: String, listId: Int, movieId: Long) {
        withContext(Dispatchers.IO) {
            userDatabaseDataSource.addMovieToList(userId, listId.toLong(), movieId)
        }
    }

    override suspend fun removeMovieFromList(userId: String, listId: Int, movieId: Long) {
        withContext(Dispatchers.IO) {
            userDatabaseDataSource.removeMovieFromList(userId, listId.toLong(), movieId)
        }
    }

    override suspend fun getRecentMovies(userId: String, limit: Int): MovieListing {
        val movies = userDatabaseDataSource.getRecentWatchedMovies(userId, limit)
        return MovieListing(
            totalPages = 1,
            totalResults = movies.size,
            movies = movies
        )
    }

    override suspend fun setMovieStatus(userId: String, movieId: Long, status: String) {
        withContext(Dispatchers.IO) {
            userDatabaseDataSource.setMovieStatus(userId, movieId, status)
        }
    }

    override suspend fun rateMovie(userId: String, movieId: Long, rating: Float) {
        withContext(Dispatchers.IO) {
            userDatabaseDataSource.rateMovie(userId, movieId, rating)
        }
    }
}
