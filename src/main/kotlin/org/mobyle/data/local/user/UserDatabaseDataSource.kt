package org.mobyle.data.local.user

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.upsert
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.not
import org.mobyle.data.local.database.MoviesTable
import org.mobyle.data.local.database.UserFollowsTable
import org.mobyle.data.local.database.UserListItemsTable
import org.mobyle.data.local.database.UserListsTable
import org.mobyle.data.local.database.UserMoviesTable
import org.mobyle.data.local.database.UsersTable
import org.mobyle.data.local.movies.MovieCatalogDataSource
import org.mobyle.domain.model.FilmowList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.mobyle.domain.model.Movie
import org.mobyle.domain.model.MovieList
import org.mobyle.domain.model.MovieListDetail
import org.mobyle.domain.model.MovieShelf
import org.mobyle.domain.model.ProfileUser
import org.mobyle.domain.model.SocialUser
import org.mobyle.domain.model.User
import org.mobyle.model.MovieListing
import org.mobyle.model.MovieListListing
import org.mobyle.model.MovieShelfListing
import org.slf4j.LoggerFactory

interface UserDatabaseDataSource {
    fun findByEmail(email: String): User?
    fun findByExternalId(userExternalId: String): User?
    fun save(user: User): User
    fun updateUser(userExternalId: String, username: String, bio: String?, avatarUrl: String?)
    fun findByUsername(prefix: String): List<String>
    fun countWatchedMovies(userExternalId: String): Int
    fun countFollowing(userExternalId: String): Int
    fun countFollowers(userExternalId: String): Int
    fun getRecentWatchedMovies(userExternalId: String, limit: Int): List<Movie>
    fun getFavoriteMovies(userExternalId: String, page: Int, pageSize: Int = 20): MovieListing
    fun getWatchlistMovies(userExternalId: String, page: Int, pageSize: Int = 20): MovieListing
    fun getWatchedMovies(userExternalId: String, page: Int, pageSize: Int = 20): MovieListing
    fun getUserShelves(userExternalId: String, page: Int, pageSize: Int = 20): MovieShelfListing
    fun getUserLists(userExternalId: String, page: Int, pageSize: Int = 20): MovieListListing
    fun getListDetail(listId: Long, page: Int, pageSize: Int = 20): MovieListDetail
    fun importMovies(userExternalId: String, movies: List<Movie>, status: String, isFavorite: Boolean = false): List<Movie>
    fun importRecentlyWatched(userExternalId: String, movies: List<Movie>): List<Movie>
    fun importLists(userExternalId: String, lists: List<FilmowList>): List<FilmowList>
    fun submitUserReview(
        userExternalId: String,
        movieTmdbId: Int,
        movieTitle: String,
        posterPath: String?,
        rating: Double,
        reviewText: String,
        isFavorite: Boolean,
        isRewatch: Boolean
    )

    // Activities
    fun getFriendsActivities(currentUserExternalId: String, page: Int = 1, pageSize: Int = 10): Pair<List<org.mobyle.domain.model.UserActivity>, Int>

    // Social methods
    fun followUser(followerExternalId: String, followedExternalId: String): Boolean
    fun unfollowUser(followerExternalId: String, followedExternalId: String): Boolean
    fun isFollowing(followerExternalId: String, followedExternalId: String): Boolean
    fun getFollowers(userExternalId: String, page: Int = 1, pageSize: Int = 50): List<ProfileUser>
    fun getFollowing(userExternalId: String, page: Int = 1, pageSize: Int = 50): List<ProfileUser>
    fun getMyFollowing(currentUserExternalId: String): List<SocialUser>
    fun searchUsers(query: String, currentUserExternalId: String): List<SocialUser>
    fun createList(userExternalId: String, name: String, description: String?, movieIds: List<Long> = emptyList()): MovieList
    fun createShelf(userExternalId: String, name: String, description: String?, color: String?, visibility: String): MovieShelf
    fun deleteList(userExternalId: String, listId: Long)
    fun addMovieToList(userExternalId: String, listId: Long, movieId: Long)
    fun removeMovieFromList(userExternalId: String, listId: Long, movieId: Long)

    // Movie status & rating
    fun setMovieStatus(userExternalId: String, movieId: Long, status: String)
    fun rateMovie(userExternalId: String, movieId: Long, rating: Float)
    fun getUserMovieStatus(userExternalId: String, movieId: Long): UserMovieStatus?
}

data class UserMovieStatus(val status: String, val rating: Float?)

class UserDatabaseDataSourceImpl(
    private val movieCatalogDataSource: MovieCatalogDataSource
) : UserDatabaseDataSource {

    private val log = LoggerFactory.getLogger(UserDatabaseDataSourceImpl::class.java)

    override fun findByEmail(email: String): User? {
        return transaction {
            UsersTable.selectAll()
                .where { UsersTable.email eq email }
                .firstOrNull()
                ?.let { row -> rowToUser(row) }
        }
    }

    override fun findByExternalId(userExternalId: String): User? {
        return transaction {
            UsersTable.selectAll()
                .where { UsersTable.externalId eq userExternalId }
                .firstOrNull()
                ?.let { row -> rowToUser(row) }
        }
    }

    private fun rowToUser(row: org.jetbrains.exposed.sql.ResultRow): User {
        return User(
            id = row[UsersTable.externalId],
            email = row[UsersTable.email] ?: "",
            username = row[UsersTable.username],
            avatar = row[UsersTable.avatarUrl],
            bio = row[UsersTable.bio],
            createdAt = row[UsersTable.createdAt].toString(),
            passwordHash = row[UsersTable.passwordHash]
        )
    }

    override fun save(user: User): User {
        return transaction {
            UsersTable.insertAndGetId {
                it[externalId] = user.id
                it[username] = user.username
                it[email] = user.email
                it[avatarUrl] = user.avatar
                it[bio] = user.bio
                it[passwordHash] = user.passwordHash
                it[createdAt] = Clock.System.now()
            }
            user
        }
    }

    override fun updateUser(userExternalId: String, username: String, bio: String?, avatarUrl: String?) {
        transaction {
            UsersTable.update({ UsersTable.externalId eq userExternalId }) {
                it[UsersTable.username] = username
                it[UsersTable.bio] = bio
                it[UsersTable.avatarUrl] = avatarUrl
            }
        }
    }

    override fun findByUsername(prefix: String): List<String> {
        return transaction {
            UsersTable.selectAll()
                .where { UsersTable.username like "$prefix%" }
                .map { it[UsersTable.username] }
        }
    }

    private fun resolveUserDbId(userExternalId: String): Long? {
        return UsersTable.selectAll()
            .where { UsersTable.externalId eq userExternalId }
            .firstOrNull()
            ?.get(UsersTable.id)?.value
    }

    override fun countWatchedMovies(userExternalId: String): Int {
        return transaction {
            val userDbId = resolveUserDbId(userExternalId) ?: return@transaction 0

            UserMoviesTable.selectAll()
                .where {
                    (UserMoviesTable.userId eq userDbId) and
                        (UserMoviesTable.status eq "watched")
                }
                .count()
                .toInt()
        }
    }

    override fun countFollowing(userExternalId: String): Int {
        return transaction {
            val userDbId = resolveUserDbId(userExternalId) ?: return@transaction 0

            UserFollowsTable.selectAll()
                .where { UserFollowsTable.followerId eq userDbId }
                .count()
                .toInt()
        }
    }

    override fun countFollowers(userExternalId: String): Int {
        return transaction {
            val userDbId = resolveUserDbId(userExternalId) ?: return@transaction 0

            UserFollowsTable.selectAll()
                .where { UserFollowsTable.followedId eq userDbId }
                .count()
                .toInt()
        }
    }

    override fun getRecentWatchedMovies(userExternalId: String, limit: Int): List<Movie> {
        return transaction {
            val userDbId = resolveUserDbId(userExternalId) ?: return@transaction emptyList()

            (UserMoviesTable innerJoin MoviesTable)
                .selectAll()
                .where {
                    (UserMoviesTable.userId eq userDbId) and
                        (UserMoviesTable.status eq "watched")
                }
                .orderBy(UserMoviesTable.watchedAt, SortOrder.DESC)
                .limit(limit)
                .map { row -> rowToMovie(row) }
        }
    }

    override fun getFavoriteMovies(userExternalId: String, page: Int, pageSize: Int): MovieListing {
        return transaction {
            val userDbId = resolveUserDbId(userExternalId)
                ?: return@transaction MovieListing(0, 0, emptyList())

            val totalResults = (UserMoviesTable innerJoin MoviesTable)
                .selectAll()
                .where {
                    (UserMoviesTable.userId eq userDbId) and
                        (UserMoviesTable.isFavorite eq true)
                }
                .count().toInt()

            val movies = (UserMoviesTable innerJoin MoviesTable)
                .selectAll()
                .where {
                    (UserMoviesTable.userId eq userDbId) and
                        (UserMoviesTable.isFavorite eq true)
                }
                .orderBy(UserMoviesTable.updatedAt, SortOrder.DESC)
                .limit(pageSize, offset = ((page - 1) * pageSize).toLong())
                .map { row -> rowToMovie(row) }

            MovieListing(
                totalPages = (totalResults + pageSize - 1) / pageSize,
                totalResults = totalResults,
                movies = movies
            )
        }
    }

    override fun getWatchlistMovies(userExternalId: String, page: Int, pageSize: Int): MovieListing {
        return transaction {
            val userDbId = resolveUserDbId(userExternalId)
                ?: return@transaction MovieListing(0, 0, emptyList())

            val totalResults = (UserMoviesTable innerJoin MoviesTable)
                .selectAll()
                .where {
                    (UserMoviesTable.userId eq userDbId) and
                        (UserMoviesTable.status eq "want_to_watch")
                }
                .count().toInt()

            val movies = (UserMoviesTable innerJoin MoviesTable)
                .selectAll()
                .where {
                    (UserMoviesTable.userId eq userDbId) and
                        (UserMoviesTable.status eq "want_to_watch")
                }
                .orderBy(UserMoviesTable.updatedAt, SortOrder.DESC)
                .limit(pageSize, offset = ((page - 1) * pageSize).toLong())
                .map { row -> rowToMovie(row) }

            MovieListing(
                totalPages = (totalResults + pageSize - 1) / pageSize,
                totalResults = totalResults,
                movies = movies
            )
        }
    }

    override fun getWatchedMovies(userExternalId: String, page: Int, pageSize: Int): MovieListing {
        return transaction {
            val userDbId = resolveUserDbId(userExternalId)
            log.info("[WATCHED] externalId=$userExternalId dbId=$userDbId page=$page")
            if (userDbId == null) return@transaction MovieListing(0, 0, emptyList())

            val totalResults = (UserMoviesTable innerJoin MoviesTable)
                .selectAll()
                .where {
                    (UserMoviesTable.userId eq userDbId) and
                        (UserMoviesTable.status eq "watched")
                }
                .count().toInt()

            val movies = (UserMoviesTable innerJoin MoviesTable)
                .selectAll()
                .where {
                    (UserMoviesTable.userId eq userDbId) and
                        (UserMoviesTable.status eq "watched")
                }
                // watchedAt alone produces ties when movies are bulk-imported at the same timestamp,
                // causing non-deterministic OFFSET behavior (duplicates across pages).
                // UserMoviesTable.id as tiebreaker gives a stable, unique order.
                .orderBy(
                    UserMoviesTable.watchedAt to SortOrder.DESC_NULLS_LAST,
                    UserMoviesTable.id to SortOrder.DESC
                )
                .limit(pageSize, offset = ((page - 1) * pageSize).toLong())
                .map { row -> rowToMovie(row) }

            log.info("[WATCHED] totalResults=$totalResults movies=${movies.size}")
            MovieListing(
                totalPages = (totalResults + pageSize - 1) / pageSize,
                totalResults = totalResults,
                movies = movies
            )
        }
    }

    override fun getUserShelves(userExternalId: String, page: Int, pageSize: Int): MovieShelfListing {
        return transaction {
            val userDbId = resolveUserDbId(userExternalId)
                ?: return@transaction MovieShelfListing(0, 0, emptyList())

            val totalResults = UserListsTable.selectAll()
                .where { UserListsTable.userId eq userDbId }
                .count().toInt()

            val shelves = UserListsTable.selectAll()
                .where { UserListsTable.userId eq userDbId }
                .orderBy(UserListsTable.createdAt, SortOrder.DESC)
                .limit(pageSize, offset = ((page - 1) * pageSize).toLong())
                .map { row ->
                    val listDbId = row[UserListsTable.id].value

                    val totalMoviesCount = (UserListItemsTable innerJoin MoviesTable)
                        .selectAll()
                        .where { UserListItemsTable.listId eq listDbId }
                        .count().toInt()

                    // Get preview movies (first 5)
                    val movies = (UserListItemsTable innerJoin MoviesTable)
                        .selectAll()
                        .where { UserListItemsTable.listId eq listDbId }
                        .orderBy(UserListItemsTable.position, SortOrder.ASC)
                        .limit(5)
                        .map { movieRow ->
                            Movie(
                                id = movieRow[MoviesTable.id].value,
                                tmdbId = movieRow[MoviesTable.tmdbId],
                                title = movieRow[MoviesTable.title],
                                posterPath = movieRow[MoviesTable.posterPath],
                                overview = movieRow[MoviesTable.overview] ?: "",
                                backdropPath = movieRow[MoviesTable.backdropPath],
                                voteAverage = movieRow[MoviesTable.voteAverage]?.toDouble() ?: 0.0
                            )
                        }

                    // Count watched movies in this list
                    val movieIdsInList = UserListItemsTable
                        .selectAll()
                        .where { UserListItemsTable.listId eq listDbId }
                        .map { it[UserListItemsTable.movieId].value }

                    val moviesWatchedCount = if (movieIdsInList.isNotEmpty()) {
                        UserMoviesTable.selectAll()
                            .where {
                                (UserMoviesTable.userId eq userDbId) and
                                    (UserMoviesTable.movieId inList movieIdsInList) and
                                    (UserMoviesTable.status eq "watched")
                            }
                            .count().toInt()
                    } else 0

                    MovieShelf(
                        id = listDbId.toInt(),
                        name = row[UserListsTable.name],
                        date = row[UserListsTable.createdAt].toString(),
                        moviesWatchedCount = moviesWatchedCount,
                        totalMoviesCount = totalMoviesCount,
                        movies = movies,
                        currentPage = 1,
                        totalPages = (totalMoviesCount + 4) / 5,
                        color = row[UserListsTable.color]
                    )
                }

            MovieShelfListing(
                totalPages = (totalResults + pageSize - 1) / pageSize,
                totalResults = totalResults,
                shelves = shelves
            )
        }
    }

    override fun getUserLists(userExternalId: String, page: Int, pageSize: Int): MovieListListing {
        return transaction {
            val userDbId = resolveUserDbId(userExternalId)
                ?: return@transaction MovieListListing(0, 0, emptyList())

            val username = UsersTable.selectAll()
                .where { UsersTable.externalId eq userExternalId }
                .firstOrNull()?.get(UsersTable.username) ?: ""

            val totalResults = UserListsTable.selectAll()
                .where { UserListsTable.userId eq userDbId }
                .count().toInt()

            val lists = UserListsTable.selectAll()
                .where { UserListsTable.userId eq userDbId }
                .orderBy(UserListsTable.createdAt, SortOrder.DESC)
                .limit(pageSize, offset = ((page - 1) * pageSize).toLong())
                .map { row ->
                    val listDbId = row[UserListsTable.id].value

                    val movieCount = (UserListItemsTable innerJoin MoviesTable)
                        .selectAll()
                        .where { UserListItemsTable.listId eq listDbId }
                        .count().toInt()

                    val posterPaths = (UserListItemsTable innerJoin MoviesTable)
                        .selectAll()
                        .where { UserListItemsTable.listId eq listDbId }
                        .orderBy(UserListItemsTable.position, SortOrder.ASC)
                        .limit(4)
                        .mapNotNull { it[MoviesTable.posterPath] }

                    MovieList(
                        id = listDbId.toInt(),
                        name = row[UserListsTable.name],
                        creator = username,
                        description = row[UserListsTable.description],
                        movieCount = movieCount,
                        posterPaths = posterPaths
                    )
                }

            MovieListListing(
                totalPages = (totalResults + pageSize - 1) / pageSize,
                totalResults = totalResults,
                lists = lists
            )
        }
    }

    override fun getListDetail(listId: Long, page: Int, pageSize: Int): MovieListDetail {
        return transaction {
            val listRow = UserListsTable.selectAll()
                .where { UserListsTable.id eq listId }
                .firstOrNull()
                ?: return@transaction MovieListDetail(
                    id = listId.toInt(), name = "", creator = ""
                )

            val userDbId = listRow[UserListsTable.userId].value
            val username = UsersTable.selectAll()
                .where { UsersTable.id eq userDbId }
                .firstOrNull()?.get(UsersTable.username) ?: ""

            val totalMovies = (UserListItemsTable innerJoin MoviesTable)
                .selectAll()
                .where { UserListItemsTable.listId eq listId }
                .count().toInt()

            val movies = (UserListItemsTable innerJoin MoviesTable)
                .selectAll()
                .where { UserListItemsTable.listId eq listId }
                .orderBy(UserListItemsTable.position, SortOrder.ASC)
                .limit(pageSize, offset = ((page - 1) * pageSize).toLong())
                .map { row ->
                    val movieDbId = row[MoviesTable.id].value
                    val userRating = UserMoviesTable.selectAll()
                        .where {
                            (UserMoviesTable.userId eq userDbId) and
                                (UserMoviesTable.movieId eq movieDbId)
                        }
                        .firstOrNull()
                        ?.get(UserMoviesTable.rating)?.toDouble()

                    Movie(
                        id = row[MoviesTable.id].value,
                        tmdbId = row[MoviesTable.tmdbId],
                        title = row[MoviesTable.title],
                        localTitle = row[MoviesTable.localTitle],
                        originalTitle = row[MoviesTable.originalTitle],
                        posterPath = row[MoviesTable.posterPath],
                        voteAverage = row[MoviesTable.voteAverage]?.toDouble() ?: 0.0,
                        userRating = userRating,
                        releaseDate = row[MoviesTable.releaseDate] ?: row[MoviesTable.year]?.toString(),
                        filmowId = row[MoviesTable.filmowId]
                    )
                }

            MovieListDetail(
                id = listId.toInt(),
                name = listRow[UserListsTable.name],
                creator = username,
                description = listRow[UserListsTable.description],
                movies = movies,
                totalMovies = totalMovies,
                totalPages = (totalMovies + pageSize - 1) / pageSize
            )
        }
    }

    private fun rowToMovie(row: org.jetbrains.exposed.sql.ResultRow): Movie {
        return Movie(
            id = row[MoviesTable.id].value,
            tmdbId = row[MoviesTable.tmdbId],
            title = row[MoviesTable.title],
            localTitle = row[MoviesTable.localTitle],
            originalTitle = row[MoviesTable.originalTitle],
            overview = row[MoviesTable.overview] ?: "",
            posterPath = row[MoviesTable.posterPath],
            backdropPath = row[MoviesTable.backdropPath],
            voteAverage = row[MoviesTable.voteAverage]?.toDouble() ?: 0.0,
            userRating = row[UserMoviesTable.rating]?.toDouble(),
            releaseDate = row[MoviesTable.releaseDate] ?: row[MoviesTable.year]?.toString(),
            filmowId = row[MoviesTable.filmowId],
            watchedAt = row[UserMoviesTable.watchedAt]?.toString()
        )
    }

    private fun ensureMovie(movie: Movie): Long {
        return movieCatalogDataSource.upsertMovie(
            movie = movie,
            filmowId = movie.filmowId
        )
    }

    override fun importMovies(
        userExternalId: String,
        movies: List<Movie>,
        status: String,
        isFavorite: Boolean
    ): List<Movie> {
        val result = mutableListOf<Movie>()
        transaction {
            val userDbId = resolveUserDbId(userExternalId) ?: return@transaction
            val now = Clock.System.now()

            for (movie in movies) {
                val movieDbId = ensureMovie(movie)
                result += movie.copy(id = movieDbId)

                if (isFavorite) {
                    // For favorites: only set isFavorite flag, don't overwrite watchedAt
                    val existing = UserMoviesTable.selectAll()
                        .where {
                            (UserMoviesTable.userId eq userDbId) and
                                (UserMoviesTable.movieId eq movieDbId) and
                                (UserMoviesTable.importSource eq "filmow")
                        }
                        .firstOrNull()

                    if (existing != null) {
                        UserMoviesTable.update({
                            (UserMoviesTable.userId eq userDbId) and
                                (UserMoviesTable.movieId eq movieDbId) and
                                (UserMoviesTable.importSource eq "filmow")
                        }) {
                            it[UserMoviesTable.isFavorite] = true
                            if (movie.userRating != null) it[rating] = movie.userRating.toFloat()
                            it[updatedAt] = now
                        }
                    } else {
                        UserMoviesTable.insert {
                            it[userId] = userDbId
                            it[movieId] = movieDbId
                            it[UserMoviesTable.status] = status
                            it[rating] = movie.userRating?.toFloat()
                            it[UserMoviesTable.isFavorite] = true
                            it[importSource] = "filmow"
                            it[importedAt] = now
                            it[createdAt] = now
                            it[updatedAt] = now
                            it[watchedAt] = now
                        }
                    }
                } else {
                    val movieWatchedAt = if (status == "watched") {
                        movie.watchedAt?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: now
                    } else null

                    UserMoviesTable.upsert(
                        UserMoviesTable.userId, UserMoviesTable.movieId, UserMoviesTable.importSource
                    ) {
                        it[userId] = userDbId
                        it[movieId] = movieDbId
                        it[UserMoviesTable.status] = status
                        it[rating] = movie.userRating?.toFloat()
                        it[UserMoviesTable.isFavorite] = false
                        it[importSource] = "filmow"
                        it[importedAt] = now
                        it[createdAt] = now
                        it[updatedAt] = now
                        if (movieWatchedAt != null) it[watchedAt] = movieWatchedAt
                    }
                }
            }
        }
        return result
    }

    override fun importRecentlyWatched(userExternalId: String, movies: List<Movie>): List<Movie> {
        val result = mutableListOf<Movie>()
        transaction {
            val userDbId = resolveUserDbId(userExternalId) ?: return@transaction
            val now = Clock.System.now()

            // Use the scraped watchedAt when available. Fall back to a staggered timestamp
            // so the profile-page order is preserved in ORDER BY watchedAt DESC.
            for ((index, movie) in movies.withIndex()) {
                val movieDbId = ensureMovie(movie)
                result += movie.copy(id = movieDbId)
                val staggeredWatchedAt = movie.watchedAt
                    ?.let { runCatching { Instant.parse(it) }.getOrNull() }
                    ?: now.plus(kotlin.time.Duration.parse("${movies.size - index}m"))

                UserMoviesTable.upsert(
                    UserMoviesTable.userId, UserMoviesTable.movieId, UserMoviesTable.importSource
                ) {
                    it[userId] = userDbId
                    it[movieId] = movieDbId
                    it[status] = "watched"
                    it[rating] = movie.userRating?.toFloat()
                    it[isFavorite] = false
                    it[importSource] = "filmow"
                    it[importedAt] = now
                    it[createdAt] = now
                    it[updatedAt] = now
                    it[watchedAt] = staggeredWatchedAt
                }
            }
        }
        return result
    }

    override fun importLists(userExternalId: String, lists: List<FilmowList>): List<FilmowList> {
        val result = mutableListOf<FilmowList>()
        transaction {
            val userDbId = resolveUserDbId(userExternalId) ?: return@transaction
            val now = Clock.System.now()

            for (filmowList in lists) {
                val listId = UserListsTable.insertAndGetId {
                    it[userId] = userDbId
                    it[name] = filmowList.title
                    it[description] = filmowList.description
                    it[isPublic] = true
                    it[createdAt] = now
                }

                val updatedMovies = mutableListOf<org.mobyle.domain.model.Movie>()
                filmowList.movies.forEachIndexed { index, movie ->
                    val movieDbId = ensureMovie(movie)
                    updatedMovies += movie.copy(id = movieDbId)

                    UserListItemsTable.upsert(UserListItemsTable.listId, UserListItemsTable.movieId) {
                        it[UserListItemsTable.listId] = listId
                        it[UserListItemsTable.movieId] = movieDbId
                        it[position] = index
                        it[addedAt] = now
                    }
                }

                result += filmowList.copy(movies = updatedMovies)
            }
        }
        return result
    }

    override fun submitUserReview(
        userExternalId: String,
        movieTmdbId: Int,
        movieTitle: String,
        posterPath: String?,
        rating: Double,
        reviewText: String,
        isFavorite: Boolean,
        isRewatch: Boolean
    ) {
        transaction {
            val userDbId = resolveUserDbId(userExternalId) ?: return@transaction
            val now = Clock.System.now()

            // Find or create the movie by TMDB ID
            val movieDbId = MoviesTable.selectAll()
                .where { MoviesTable.tmdbId eq movieTmdbId }
                .firstOrNull()
                ?.get(MoviesTable.id)?.value
                ?: MoviesTable.insertAndGetId {
                    it[tmdbId] = movieTmdbId
                    it[title] = movieTitle
                    it[MoviesTable.posterPath] = posterPath
                    it[needsEnrichment] = true
                }.value

            // Check if there's already a manual review for this movie
            val existing = UserMoviesTable.selectAll()
                .where {
                    (UserMoviesTable.userId eq userDbId) and
                        (UserMoviesTable.movieId eq movieDbId) and
                        (UserMoviesTable.importSource eq "manual")
                }
                .firstOrNull()

            if (existing != null) {
                UserMoviesTable.update({
                    (UserMoviesTable.userId eq userDbId) and
                        (UserMoviesTable.movieId eq movieDbId) and
                        (UserMoviesTable.importSource eq "manual")
                }) {
                    it[status] = "watched"
                    it[UserMoviesTable.rating] = rating.toFloat()
                    it[review] = reviewText
                    it[UserMoviesTable.isFavorite] = isFavorite
                    it[rewatches] = if (isRewatch) existing[rewatches] + 1 else existing[rewatches]
                    it[watchedAt] = now
                    it[updatedAt] = now
                }
            } else {
                UserMoviesTable.insert {
                    it[userId] = userDbId
                    it[movieId] = movieDbId
                    it[status] = "watched"
                    it[UserMoviesTable.rating] = rating.toFloat()
                    it[review] = reviewText
                    it[UserMoviesTable.isFavorite] = isFavorite
                    it[rewatches] = if (isRewatch) 1 else 0
                    it[importSource] = "manual"
                    it[watchedAt] = now
                    it[createdAt] = now
                    it[updatedAt] = now
                }
            }
        }
    }

    // ── Activities ─────────────────────────────────────────────────────────

    override fun getFriendsActivities(
        currentUserExternalId: String,
        page: Int,
        pageSize: Int
    ): Pair<List<org.mobyle.domain.model.UserActivity>, Int> {
        return transaction {
            val currentUserDbId = resolveUserDbId(currentUserExternalId)
                ?: return@transaction Pair(emptyList(), 0)

            // Get IDs of users the current user follows
            val followedDbIds = UserFollowsTable.selectAll()
                .where { UserFollowsTable.followerId eq currentUserDbId }
                .map { it[UserFollowsTable.followedId].value }

            if (followedDbIds.isEmpty()) return@transaction Pair(emptyList(), 0)

            // Count total activities from followed users
            val totalResults = (UserMoviesTable innerJoin MoviesTable)
                .selectAll()
                .where { UserMoviesTable.userId inList followedDbIds }
                .count()
                .toInt()

            val totalPages = if (totalResults == 0) 0 else (totalResults + pageSize - 1) / pageSize

            // Get paginated activities
            val activities = (UserMoviesTable innerJoin MoviesTable)
                .selectAll()
                .where { UserMoviesTable.userId inList followedDbIds }
                .orderBy(UserMoviesTable.updatedAt, SortOrder.DESC)
                .limit(pageSize, offset = ((page - 1) * pageSize).toLong())
                .map { row ->
                    val userDbId = row[UserMoviesTable.userId].value
                    val username = UsersTable.selectAll()
                        .where { UsersTable.id eq userDbId }
                        .firstOrNull()?.get(UsersTable.username) ?: "Unknown"

                    val status = row[UserMoviesTable.status]
                    val hasReview = !row[UserMoviesTable.review].isNullOrBlank()
                    val movieTitle = row[MoviesTable.title]

                    val action = when {
                        hasReview -> "Reviewed"
                        status == "watched" -> "Watched"
                        status == "want_to_watch" -> "Added to watchlist"
                        else -> "Watched"
                    }

                    val time = row[UserMoviesTable.updatedAt].toString()

                    org.mobyle.domain.model.UserActivity(
                        userName = username,
                        action = action,
                        movie = movieTitle,
                        time = time
                    )
                }

            Pair(activities, totalPages)
        }
    }

    // ── Social ──────────────────────────────────────────────────────────────

    override fun followUser(followerExternalId: String, followedExternalId: String): Boolean {
        return transaction {
            val followerDbId = resolveUserDbId(followerExternalId) ?: return@transaction false
            val followedDbId = resolveUserDbId(followedExternalId) ?: return@transaction false

            val alreadyFollowing = UserFollowsTable.selectAll()
                .where { (UserFollowsTable.followerId eq followerDbId) and (UserFollowsTable.followedId eq followedDbId) }
                .count() > 0

            if (alreadyFollowing) return@transaction false

            UserFollowsTable.insert {
                it[followerId] = followerDbId
                it[followedId] = followedDbId
                it[createdAt] = Clock.System.now()
            }
            true
        }
    }

    override fun unfollowUser(followerExternalId: String, followedExternalId: String): Boolean {
        return transaction {
            val followerDbId = resolveUserDbId(followerExternalId) ?: return@transaction false
            val followedDbId = resolveUserDbId(followedExternalId) ?: return@transaction false

            val deleted = UserFollowsTable.deleteWhere {
                (followerId eq followerDbId) and (followedId eq followedDbId)
            }
            deleted > 0
        }
    }

    override fun isFollowing(followerExternalId: String, followedExternalId: String): Boolean {
        return transaction {
            val followerDbId = resolveUserDbId(followerExternalId) ?: return@transaction false
            val followedDbId = resolveUserDbId(followedExternalId) ?: return@transaction false

            UserFollowsTable.selectAll()
                .where { (UserFollowsTable.followerId eq followerDbId) and (UserFollowsTable.followedId eq followedDbId) }
                .count() > 0
        }
    }

    override fun getFollowers(userExternalId: String, page: Int, pageSize: Int): List<ProfileUser> {
        return transaction {
            val userDbId = resolveUserDbId(userExternalId) ?: return@transaction emptyList()

            UserFollowsTable
                .join(UsersTable, org.jetbrains.exposed.sql.JoinType.INNER, UserFollowsTable.followerId, UsersTable.id)
                .selectAll()
                .where { UserFollowsTable.followedId eq userDbId }
                .orderBy(UserFollowsTable.createdAt, SortOrder.DESC)
                .limit(pageSize, offset = ((page - 1) * pageSize).toLong())
                .map { row ->
                    val username = row[UsersTable.username]
                    ProfileUser(
                        id = row[UsersTable.externalId],
                        displayName = username,
                        initials = computeInitials(username)
                    )
                }
        }
    }

    override fun getFollowing(userExternalId: String, page: Int, pageSize: Int): List<ProfileUser> {
        return transaction {
            val userDbId = resolveUserDbId(userExternalId) ?: return@transaction emptyList()

            UserFollowsTable
                .join(UsersTable, org.jetbrains.exposed.sql.JoinType.INNER, UserFollowsTable.followedId, UsersTable.id)
                .selectAll()
                .where { UserFollowsTable.followerId eq userDbId }
                .orderBy(UserFollowsTable.createdAt, SortOrder.DESC)
                .limit(pageSize, offset = ((page - 1) * pageSize).toLong())
                .map { row ->
                    val username = row[UsersTable.username]
                    ProfileUser(
                        id = row[UsersTable.externalId],
                        displayName = username,
                        initials = computeInitials(username)
                    )
                }
        }
    }

    override fun getMyFollowing(currentUserExternalId: String): List<SocialUser> {
        return transaction {
            val currentUserDbId = resolveUserDbId(currentUserExternalId) ?: return@transaction emptyList()

            UserFollowsTable
                .join(UsersTable, org.jetbrains.exposed.sql.JoinType.INNER, UserFollowsTable.followedId, UsersTable.id)
                .selectAll()
                .where { UserFollowsTable.followerId eq currentUserDbId }
                .orderBy(UserFollowsTable.createdAt, SortOrder.DESC)
                .map { row ->
                    val followedExternalId = row[UsersTable.externalId]
                    val username = row[UsersTable.username]
                    val followedDbId = row[UsersTable.id].value

                    val moviesWatchedCount = UserMoviesTable.selectAll()
                        .where {
                            (UserMoviesTable.userId eq followedDbId) and
                                (UserMoviesTable.status eq "watched")
                        }
                        .count()
                        .toInt()

                    SocialUser(
                        id = followedExternalId,
                        displayName = username,
                        initials = computeInitials(username),
                        moviesWatchedCount = moviesWatchedCount,
                        isFollowing = true
                    )
                }
        }
    }

    override fun searchUsers(query: String, currentUserExternalId: String): List<SocialUser> {
        return transaction {
            val currentUserDbId = resolveUserDbId(currentUserExternalId) ?: return@transaction emptyList()

            val followedIds = UserFollowsTable.selectAll()
                .where { UserFollowsTable.followerId eq currentUserDbId }
                .map { it[UserFollowsTable.followedId].value }
                .toSet()

            UsersTable.selectAll()
                .where { (UsersTable.username like "%${query.lowercase()}%") and not(UsersTable.externalId eq currentUserExternalId) }
                .limit(20)
                .map { row ->
                    val userDbId = row[UsersTable.id].value
                    val username = row[UsersTable.username]

                    val moviesWatchedCount = UserMoviesTable.selectAll()
                        .where {
                            (UserMoviesTable.userId eq userDbId) and
                                (UserMoviesTable.status eq "watched")
                        }
                        .count()
                        .toInt()

                    SocialUser(
                        id = row[UsersTable.externalId],
                        displayName = username,
                        initials = computeInitials(username),
                        moviesWatchedCount = moviesWatchedCount,
                        isFollowing = userDbId in followedIds
                    )
                }
        }
    }

    override fun createList(userExternalId: String, name: String, description: String?, movieIds: List<Long>): MovieList {
        return transaction {
            val userDbId = resolveUserDbId(userExternalId)
                ?: throw IllegalArgumentException("User not found")

            val username = UsersTable.selectAll()
                .where { UsersTable.externalId eq userExternalId }
                .firstOrNull()?.get(UsersTable.username) ?: ""

            val now = Clock.System.now()
            val listId = UserListsTable.insertAndGetId {
                it[userId] = userDbId
                it[UserListsTable.name] = name
                it[UserListsTable.description] = description
                it[isPublic] = true
                it[createdAt] = now
            }

            val posterPaths = mutableListOf<String>()
            for ((position, movieDbId) in movieIds.withIndex()) {
                UserListItemsTable.insert {
                    it[UserListItemsTable.listId] = listId
                    it[UserListItemsTable.movieId] = org.jetbrains.exposed.dao.id.EntityID(movieDbId, MoviesTable)
                    it[UserListItemsTable.position] = position
                    it[addedAt] = now
                }
                if (posterPaths.size < 4) {
                    MoviesTable.selectAll()
                        .where { MoviesTable.id eq movieDbId }
                        .firstOrNull()?.get(MoviesTable.posterPath)?.let { posterPaths.add(it) }
                }
            }

            MovieList(
                id = listId.value.toInt(),
                name = name,
                creator = username,
                description = description,
                movieCount = movieIds.size,
                posterPaths = posterPaths
            )
        }
    }

    override fun createShelf(
        userExternalId: String,
        name: String,
        description: String?,
        color: String?,
        visibility: String
    ): MovieShelf {
        return transaction {
            val userDbId = resolveUserDbId(userExternalId)
                ?: throw IllegalArgumentException("User not found")

            val now = Clock.System.now()
            val listId = UserListsTable.insertAndGetId {
                it[userId] = userDbId
                it[UserListsTable.name] = name
                it[UserListsTable.description] = description
                it[UserListsTable.color] = color
                it[UserListsTable.visibility] = visibility
                it[isPublic] = visibility == "public"
                it[createdAt] = now
            }

            MovieShelf(
                id = listId.value.toInt(),
                name = name,
                date = now.toString(),
                moviesWatchedCount = 0,
                totalMoviesCount = 0,
                movies = emptyList(),
                currentPage = 1,
                totalPages = 0,
                color = color
            )
        }
    }

    override fun deleteList(userExternalId: String, listId: Long) {
        transaction {
            val userDbId = resolveUserDbId(userExternalId)
                ?: throw IllegalArgumentException("User not found")

            val listRow = UserListsTable.selectAll()
                .where { (UserListsTable.id eq listId) and (UserListsTable.userId eq userDbId) }
                .firstOrNull()
                ?: throw IllegalArgumentException("List not found or not owned by user")

            UserListItemsTable.deleteWhere { UserListItemsTable.listId eq listId }
            UserListsTable.deleteWhere { UserListsTable.id eq listId }
        }
    }

    override fun addMovieToList(userExternalId: String, listId: Long, movieId: Long) {
        transaction {
            val userDbId = resolveUserDbId(userExternalId)
                ?: throw IllegalArgumentException("User not found")

            UserListsTable.selectAll()
                .where { (UserListsTable.id eq listId) and (UserListsTable.userId eq userDbId) }
                .firstOrNull()
                ?: throw IllegalArgumentException("List not found or not owned by user")

            val maxPosition = UserListItemsTable.selectAll()
                .where { UserListItemsTable.listId eq listId }
                .maxOfOrNull { it[UserListItemsTable.position] } ?: -1

            UserListItemsTable.insert {
                it[UserListItemsTable.listId] = listId
                it[UserListItemsTable.movieId] = movieId
                it[position] = maxPosition + 1
                it[addedAt] = Clock.System.now()
            }
        }
    }

    override fun removeMovieFromList(userExternalId: String, listId: Long, movieId: Long) {
        transaction {
            val userDbId = resolveUserDbId(userExternalId)
                ?: throw IllegalArgumentException("User not found")

            UserListsTable.selectAll()
                .where { (UserListsTable.id eq listId) and (UserListsTable.userId eq userDbId) }
                .firstOrNull()
                ?: throw IllegalArgumentException("List not found or not owned by user")

            UserListItemsTable.deleteWhere {
                (UserListItemsTable.listId eq listId) and (UserListItemsTable.movieId eq movieId)
            }
        }
    }

    override fun setMovieStatus(userExternalId: String, movieId: Long, status: String) {
        transaction {
            val userDbId = resolveUserDbId(userExternalId) ?: return@transaction
            val now = Clock.System.now()

            val existing = UserMoviesTable.selectAll()
                .where {
                    (UserMoviesTable.userId eq userDbId) and
                        (UserMoviesTable.movieId eq movieId) and
                        (UserMoviesTable.importSource eq "manual")
                }
                .firstOrNull()

            if (existing != null) {
                UserMoviesTable.update({
                    (UserMoviesTable.userId eq userDbId) and
                        (UserMoviesTable.movieId eq movieId) and
                        (UserMoviesTable.importSource eq "manual")
                }) {
                    it[UserMoviesTable.status] = status
                    it[updatedAt] = now
                    it[watchedAt] = if (status == "watched") now else null
                }
            } else {
                UserMoviesTable.insert {
                    it[userId] = userDbId
                    it[UserMoviesTable.movieId] = movieId
                    it[UserMoviesTable.status] = status
                    it[importSource] = "manual"
                    it[watchedAt] = if (status == "watched") now else null
                    it[createdAt] = now
                    it[updatedAt] = now
                }
            }
        }
    }

    override fun rateMovie(userExternalId: String, movieId: Long, rating: Float) {
        transaction {
            val userDbId = resolveUserDbId(userExternalId) ?: return@transaction
            val now = Clock.System.now()

            val existing = UserMoviesTable.selectAll()
                .where {
                    (UserMoviesTable.userId eq userDbId) and
                        (UserMoviesTable.movieId eq movieId) and
                        (UserMoviesTable.importSource eq "manual")
                }
                .firstOrNull()

            if (existing != null) {
                UserMoviesTable.update({
                    (UserMoviesTable.userId eq userDbId) and
                        (UserMoviesTable.movieId eq movieId) and
                        (UserMoviesTable.importSource eq "manual")
                }) {
                    it[UserMoviesTable.rating] = rating
                    it[updatedAt] = now
                }
            } else {
                UserMoviesTable.insert {
                    it[userId] = userDbId
                    it[UserMoviesTable.movieId] = movieId
                    it[UserMoviesTable.rating] = rating
                    it[status] = "watched"
                    it[importSource] = "manual"
                    it[watchedAt] = now
                    it[createdAt] = now
                    it[updatedAt] = now
                }
            }
        }
    }

    override fun getUserMovieStatus(userExternalId: String, movieId: Long): UserMovieStatus? {
        return transaction {
            val userDbId = resolveUserDbId(userExternalId) ?: return@transaction null

            UserMoviesTable.selectAll()
                .where {
                    (UserMoviesTable.userId eq userDbId) and
                        (UserMoviesTable.movieId eq movieId)
                }
                .firstOrNull()
                ?.let { row ->
                    UserMovieStatus(
                        status = row[UserMoviesTable.status],
                        rating = row[UserMoviesTable.rating]
                    )
                }
        }
    }

    private fun computeInitials(username: String): String {
        val parts = username.split(Regex("[_\\s.]+")).filter { it.isNotBlank() }
        return when {
            parts.size >= 2 -> "${parts[0].first().uppercaseChar()}${parts[1].first().uppercaseChar()}"
            parts.size == 1 && parts[0].length >= 2 -> parts[0].take(2).uppercase()
            parts.size == 1 -> parts[0].first().uppercaseChar().toString()
            else -> "??"
        }
    }
}
