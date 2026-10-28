package org.mobyle.data.local.user

import kotlinx.datetime.Clock
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.not
import org.mobyle.domain.usecase.users.isValidHandle
import org.mobyle.data.local.database.MoviesTable
import org.mobyle.data.local.database.UserFollowsTable
import org.mobyle.data.local.database.UserMoviesTable
import org.mobyle.data.local.database.UsersTable
import org.mobyle.data.local.shelves.SystemShelves
import org.mobyle.domain.model.Movie
import org.mobyle.domain.model.SocialUser
import org.mobyle.domain.model.User

interface UserDatabaseDataSource {
    fun findByEmail(email: String): User?
    fun findByExternalId(userExternalId: String): User?
    fun save(user: User): User
    fun updateUser(userExternalId: String, username: String, bio: String?, avatarUrl: String?)
    fun findByUsername(prefix: String): List<String>
    fun isUsernameTaken(username: String, exceptUserExternalId: String): Boolean
    fun countWatchedMovies(userExternalId: String): Int
    fun countFollowing(userExternalId: String): Int
    fun countFollowers(userExternalId: String): Int
    fun getRecentWatchedMovies(userExternalId: String, limit: Int): List<Movie>
    fun searchUsers(query: String, currentUserExternalId: String): List<SocialUser>
}

class UserDatabaseDataSourceImpl : UserDatabaseDataSource {

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
            val now = Clock.System.now()
            // The username becomes the @handle when it already fits the format and is free (B5).
            val defaultHandle = user.username.lowercase().takeIf { candidate ->
                isValidHandle(candidate) &&
                    UsersTable.selectAll().where { UsersTable.handle eq candidate }.empty()
            }
            val userDbId = UsersTable.insertAndGetId {
                it[externalId] = user.id
                it[username] = user.username
                it[email] = user.email
                it[avatarUrl] = user.avatar
                it[bio] = user.bio
                it[passwordHash] = user.passwordHash
                it[createdAt] = now
                it[handle] = defaultHandle
            }
            // Every account starts with "Já assisti" and "Quero ver" (F1.2).
            SystemShelves.ensure(userDbId.value, now)
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

    override fun isUsernameTaken(username: String, exceptUserExternalId: String): Boolean {
        return transaction {
            UsersTable.selectAll()
                .where { (UsersTable.username eq username) and (UsersTable.externalId neq exceptUserExternalId) }
                .any()
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
            releaseDate = row[MoviesTable.releaseDate] ?: row[MoviesTable.year]?.toString(),
            filmowId = row[MoviesTable.filmowId],
            watchedAt = row[UserMoviesTable.watchedAt]?.toString()
        )
    }

    // ── Activities ─────────────────────────────────────────────────────────

    // ── Social ──────────────────────────────────────────────────────────────

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

    // ── Locadoras ─────────────────────────────────────────────────────────

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
