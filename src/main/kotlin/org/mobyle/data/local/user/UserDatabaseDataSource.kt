package org.mobyle.data.local.user

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.upsert
import org.mobyle.data.local.database.MoviesTable
import org.mobyle.data.local.database.UserFollowsTable
import org.mobyle.data.local.database.UserListItemsTable
import org.mobyle.data.local.database.UserListsTable
import org.mobyle.data.local.database.UserMoviesTable
import org.mobyle.data.local.database.UsersTable
import org.mobyle.data.local.movies.MovieCatalogDataSource
import org.mobyle.domain.model.FilmowList
import org.mobyle.domain.model.Movie
import org.mobyle.domain.model.MovieList
import org.mobyle.domain.model.MovieListDetail
import org.mobyle.domain.model.User
import org.mobyle.model.MovieListing
import org.mobyle.model.MovieListListing
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
}

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
}
