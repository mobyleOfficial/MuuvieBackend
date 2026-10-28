package org.mobyle.data.local.shelves

import kotlinx.datetime.Instant
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.max
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.mobyle.data.local.database.ShelfItemsTable
import org.mobyle.data.local.database.ShelvesTable
import org.mobyle.data.local.database.UserMoviesTable
import org.mobyle.domain.model.vocabulary.ShelfSort
import org.mobyle.domain.model.vocabulary.SystemShelfType
import org.mobyle.domain.model.vocabulary.Visibility

/**
 * Membership rules of the system shelves (domain ADR, rules 2 and 3). Every function runs
 * inside the caller's transaction, so the shelf and user_movies.status change together.
 *
 * "Já assisti" holds the movies with status `watched`, "Quero ver" the ones with
 * `want_to_watch`; a movie is never on both. Any other status keeps it off both.
 */
internal object SystemShelves {
    const val STATUS_WATCHED = "watched"
    const val STATUS_WANT_TO_WATCH = "want_to_watch"
    // No situation: e.g. unmarked as watched but still rated or in a regular shelf.
    const val STATUS_NONE = "none"

    fun defaultName(type: SystemShelfType): String = when (type) {
        SystemShelfType.WATCHED -> "Já assisti"
        SystemShelfType.WATCHLIST -> "Quero ver"
    }

    fun statusOf(type: SystemShelfType): String = when (type) {
        SystemShelfType.WATCHED -> STATUS_WATCHED
        SystemShelfType.WATCHLIST -> STATUS_WANT_TO_WATCH
    }

    /** Creates the missing system shelves of the user and returns both ids. */
    fun ensure(userDbId: Long, now: Instant): Map<SystemShelfType, Long> {
        val existing = ShelvesTable.selectAll()
            .where { (ShelvesTable.userId eq userDbId) and ShelvesTable.systemType.isNotNull() }
            .associate { SystemShelfType.fromWireValue(it[ShelvesTable.systemType]!!)!! to it[ShelvesTable.id].value }
        if (existing.size == SystemShelfType.entries.size) return existing

        return SystemShelfType.entries.associateWith { type ->
            existing[type] ?: ShelvesTable.insertAndGetId {
                it[userId] = userDbId
                it[name] = defaultName(type)
                it[visibility] = Visibility.PUBLIC.wireValue
                it[systemType] = type.wireValue
                it[position] = type.ordinal
                it[sortMode] = ShelfSort.ADDED_NEWEST.wireValue
                it[createdAt] = now
                it[updatedAt] = now
            }.value
        }
    }

    /** Puts the movie on the system shelf that matches [status] and takes it off the other one. */
    fun applyStatus(userDbId: Long, movieDbId: Long, status: String, at: Instant) {
        val target = when (status) {
            STATUS_WATCHED -> SystemShelfType.WATCHED
            STATUS_WANT_TO_WATCH -> SystemShelfType.WATCHLIST
            else -> null
        }
        for ((type, shelfId) in ensure(userDbId, at)) {
            if (type == target) addItem(shelfId, movieDbId, at) else removeItem(shelfId, movieDbId)
        }
    }

    /**
     * Sets the user's status for the movie and the system shelves with it. A manual change
     * takes ownership of the row, so imports stop overwriting it.
     */
    fun setStatus(userDbId: Long, movieDbId: Long, status: String, at: Instant) {
        val existing = UserMoviesTable.selectAll()
            .where { (UserMoviesTable.userId eq userDbId) and (UserMoviesTable.movieId eq movieDbId) }
            .firstOrNull()
        when {
            existing != null -> UserMoviesTable.update({ UserMoviesTable.id eq existing[UserMoviesTable.id] }) {
                it[UserMoviesTable.status] = status
                if (status == STATUS_WATCHED && existing[UserMoviesTable.watchedAt] == null) it[watchedAt] = at
                it[importSource] = "manual"
                it[updatedAt] = at
            }
            status != STATUS_NONE -> UserMoviesTable.insert {
                it[userId] = userDbId
                it[movieId] = movieDbId
                it[UserMoviesTable.status] = status
                if (status == STATUS_WATCHED) it[watchedAt] = at
                it[importSource] = "manual"
                it[createdAt] = at
                it[updatedAt] = at
            }
        }
        applyStatus(userDbId, movieDbId, status, at)
    }

    /** Takes the movie off a system shelf, clearing the status only if it still points to that shelf. */
    fun unsetStatus(userDbId: Long, movieDbId: Long, type: SystemShelfType, at: Instant) {
        val current = UserMoviesTable.selectAll()
            .where { (UserMoviesTable.userId eq userDbId) and (UserMoviesTable.movieId eq movieDbId) }
            .firstOrNull()
            ?.get(UserMoviesTable.status)
        if (current == statusOf(type)) {
            setStatus(userDbId, movieDbId, STATUS_NONE, at)
        } else {
            ensure(userDbId, at)[type]?.let { removeItem(it, movieDbId) }
        }
    }

    /** Adds the movie at the end of the shelf. Returns false when it was already there. */
    fun addItem(shelfId: Long, movieDbId: Long, at: Instant): Boolean {
        val exists = ShelfItemsTable.selectAll()
            .where { (ShelfItemsTable.shelfId eq shelfId) and (ShelfItemsTable.movieId eq movieDbId) }
            .any()
        if (exists) return false
        val maxPosition = ShelfItemsTable.select(ShelfItemsTable.position.max())
            .where { ShelfItemsTable.shelfId eq shelfId }
            .firstOrNull()
            ?.get(ShelfItemsTable.position.max()) ?: -1
        ShelfItemsTable.insert {
            it[ShelfItemsTable.shelfId] = shelfId
            it[movieId] = movieDbId
            it[position] = maxPosition + 1
            it[addedAt] = at
        }
        return true
    }

    fun removeItem(shelfId: Long, movieDbId: Long) {
        ShelfItemsTable.deleteWhere { (ShelfItemsTable.shelfId eq shelfId) and (movieId eq movieDbId) }
    }
}
