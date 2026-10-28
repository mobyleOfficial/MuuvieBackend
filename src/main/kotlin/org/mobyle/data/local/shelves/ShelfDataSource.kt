package org.mobyle.data.local.shelves

import kotlinx.datetime.Clock
import org.jetbrains.exposed.sql.Case
import org.jetbrains.exposed.sql.Expression
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.SqlExpressionBuilder.notInSubQuery
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.exists
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.intLiteral
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.max
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.mobyle.data.local.database.GenresTable
import org.mobyle.data.local.database.LocadoraShelvesTable
import org.mobyle.data.local.database.LocadorasTable
import org.mobyle.data.local.database.MovieGenresTable
import org.mobyle.data.local.database.MoviesTable
import org.mobyle.data.local.database.ShelfItemsTable
import org.mobyle.data.local.database.ShelfSavesTable
import org.mobyle.data.local.database.ShelfVisitsTable
import org.mobyle.data.local.database.ShelvesTable
import org.mobyle.data.local.database.UserFollowsTable
import org.mobyle.data.local.database.UserMoviesTable
import org.mobyle.data.local.database.UsersTable
import org.mobyle.data.local.feed.toUserSummary
import org.mobyle.domain.model.Page
import org.mobyle.domain.model.PageRequest
import org.mobyle.domain.model.PublicShelf
import org.mobyle.domain.model.Shelf
import org.mobyle.domain.model.ShelfDraft
import org.mobyle.domain.model.ShelfMovie
import org.mobyle.domain.model.ShelfMovieFilter
import org.mobyle.domain.model.ShelfTransferMode
import org.mobyle.domain.model.vocabulary.Rating
import org.mobyle.domain.model.vocabulary.ShelfSort
import org.mobyle.domain.model.vocabulary.SystemShelfType
import org.mobyle.domain.model.vocabulary.Visibility

/** Shelves and shelf membership (B1). Users are identified by their external id. */
interface ShelfDataSource {
    fun userExists(userId: String): Boolean
    fun areMutuals(userId: String, otherUserId: String): Boolean
    fun ensureSystemShelves(userId: String)
    fun getShelf(shelfId: Long): Shelf?
    fun getUserShelves(
        ownerId: String,
        visibilities: Set<Visibility>,
        page: PageRequest,
        excludeHiddenByLocadoras: Boolean = false
    ): Page<Shelf>
    fun searchShelves(ownerId: String, query: String, page: PageRequest): Page<Shelf>
    fun createShelf(ownerId: String, draft: ShelfDraft): Shelf
    fun updateShelf(shelfId: Long, draft: ShelfDraft): Shelf
    fun deleteShelf(shelfId: Long)
    fun getShelfMovies(shelf: Shelf, filter: ShelfMovieFilter, page: PageRequest): Page<ShelfMovie>
    fun resolveMovieId(movieId: Long?, tmdbId: Int?, title: String?): Long?
    fun findMovieIdByTmdbId(tmdbId: Int): Long?
    fun shelfIdsWithMovie(ownerId: String, movieId: Long): List<Long>
    fun publicShelvesWithMovie(movieId: Long, page: PageRequest): Page<PublicShelf>
    fun addMovie(shelf: Shelf, movieId: Long)
    fun removeMovie(shelf: Shelf, movieId: Long)
    fun transferMovies(from: Shelf, to: Shelf, mode: ShelfTransferMode, movieIds: List<Long>)
    fun sortShelf(shelfId: Long, sortMode: ShelfSort, applyOnce: Boolean)
    fun reorderMovies(shelfId: Long, movieIds: List<Long>)
    fun reorderShelves(ownerId: String, shelfIds: List<Long>, pinnedIds: List<Long>?)

    fun isSaved(shelfId: Long, userId: String): Boolean
    fun saveShelf(shelfId: Long, userId: String)
    fun unsaveShelf(shelfId: Long, userId: String)
    /** Shelves [userId] saved and can still see, the last saved first (B14.4). */
    fun savedShelves(userId: String, page: PageRequest): Page<Shelf>
    /** Keeps the last time [userId] opened the shelf. */
    fun recordVisit(shelfId: Long, userId: String)
}

class ShelfDataSourceImpl : ShelfDataSource {

    override fun userExists(userId: String): Boolean = transaction { userDbId(userId) != null }

    override fun areMutuals(userId: String, otherUserId: String): Boolean = transaction {
        val a = userDbId(userId) ?: return@transaction false
        val b = userDbId(otherUserId) ?: return@transaction false
        UserFollowsTable.selectAll()
            .where {
                ((UserFollowsTable.followerId eq a) and (UserFollowsTable.followedId eq b)) or
                    ((UserFollowsTable.followerId eq b) and (UserFollowsTable.followedId eq a))
            }
            .count() == 2L
    }

    override fun ensureSystemShelves(userId: String) {
        transaction {
            val ownerDbId = userDbId(userId) ?: return@transaction
            SystemShelves.ensure(ownerDbId, Clock.System.now())
        }
    }

    override fun getShelf(shelfId: Long): Shelf? = transaction {
        (ShelvesTable innerJoin UsersTable).selectAll()
            .where { ShelvesTable.id eq shelfId }
            .firstOrNull()
            ?.let { toShelf(it) }
    }

    override fun getUserShelves(
        ownerId: String,
        visibilities: Set<Visibility>,
        page: PageRequest,
        excludeHiddenByLocadoras: Boolean
    ): Page<Shelf> = shelvesPage(ownerId, page) {
        val visible = ShelvesTable.visibility inList visibilities.map { it.wireValue }
        if (!excludeHiddenByLocadoras) {
            visible
        } else {
            visible and (ShelvesTable.id notInSubQuery (LocadoraShelvesTable innerJoin LocadorasTable)
                .select(LocadoraShelvesTable.shelfId)
                .where { LocadorasTable.hideShelvesInProfile eq true })
        }
    }

    override fun searchShelves(ownerId: String, query: String, page: PageRequest): Page<Shelf> =
        shelvesPage(ownerId, page) {
            if (query.isEmpty()) Op.TRUE else ShelvesTable.name.lowerCase() like "%${escapeLike(query.lowercase())}%"
        }

    private fun shelvesPage(ownerId: String, page: PageRequest, condition: () -> Op<Boolean>): Page<Shelf> = transaction {
        val ownerDbId = userDbId(ownerId) ?: return@transaction Page.of(emptyList(), page, 0)
        val query = (ShelvesTable innerJoin UsersTable).selectAll()
            .where { (ShelvesTable.userId eq ownerDbId) and condition() }
        val total = query.count().toInt()
        val shelves = query
            .orderBy(ShelvesTable.pinned to SortOrder.DESC, ShelvesTable.position to SortOrder.ASC, ShelvesTable.id to SortOrder.ASC)
            .limit(page.limit, offset = page.offset)
            .map { toShelf(it) }
        Page.of(shelves, page, total)
    }

    override fun createShelf(ownerId: String, draft: ShelfDraft): Shelf = transaction {
        val ownerDbId = userDbId(ownerId) ?: throw IllegalStateException("User not found")
        val now = Clock.System.now()
        SystemShelves.ensure(ownerDbId, now)
        val maxPosition = ShelvesTable.select(ShelvesTable.position.max())
            .where { ShelvesTable.userId eq ownerDbId }
            .firstOrNull()
            ?.get(ShelvesTable.position.max()) ?: -1
        val shelfId = ShelvesTable.insertAndGetId {
            it[userId] = ownerDbId
            it[name] = draft.name
            it[description] = draft.description
            it[color] = draft.color
            it[visibility] = draft.visibility.wireValue
            it[position] = maxPosition + 1
            it[createdAt] = now
            it[updatedAt] = now
        }.value
        getShelf(shelfId)!!
    }

    override fun updateShelf(shelfId: Long, draft: ShelfDraft): Shelf = transaction {
        ShelvesTable.update({ ShelvesTable.id eq shelfId }) {
            it[name] = draft.name
            it[description] = draft.description
            it[color] = draft.color
            it[visibility] = draft.visibility.wireValue
            it[updatedAt] = Clock.System.now()
        }
        getShelf(shelfId)!!
    }

    override fun deleteShelf(shelfId: Long) {
        transaction {
            LocadoraShelvesTable.deleteWhere { LocadoraShelvesTable.shelfId eq shelfId }
            ShelfItemsTable.deleteWhere { ShelfItemsTable.shelfId eq shelfId }
            ShelvesTable.deleteWhere { ShelvesTable.id eq shelfId }
        }
    }

    override fun getShelfMovies(shelf: Shelf, filter: ShelfMovieFilter, page: PageRequest): Page<ShelfMovie> = transaction {
        val ownerDbId = userDbId(shelf.ownerId) ?: return@transaction Page.of(emptyList(), page, 0)
        val query = shelfMoviesQuery(shelf.id, ownerDbId, filter)
        val total = query.count().toInt()
        val movies = query
            .orderBy(*orderOf(shelf.sortMode))
            .limit(page.limit, offset = page.offset)
            .map { toShelfMovie(it) }
        Page.of(movies, page, total)
    }

    override fun resolveMovieId(movieId: Long?, tmdbId: Int?, title: String?): Long? = transaction {
        when {
            movieId != null -> MoviesTable.selectAll().where { MoviesTable.id eq movieId }.firstOrNull()?.get(MoviesTable.id)?.value
            tmdbId != null -> MoviesTable.selectAll().where { MoviesTable.tmdbId eq tmdbId }.firstOrNull()?.get(MoviesTable.id)?.value
                // Unknown locally: a stub the enrichment loop completes from TMDB.
                ?: MoviesTable.insertAndGetId {
                    it[MoviesTable.tmdbId] = tmdbId
                    it[MoviesTable.title] = title.orEmpty()
                    it[needsEnrichment] = true
                }.value
            else -> null
        }
    }

    override fun findMovieIdByTmdbId(tmdbId: Int): Long? = transaction {
        MoviesTable.selectAll().where { MoviesTable.tmdbId eq tmdbId }.firstOrNull()?.get(MoviesTable.id)?.value
    }

    override fun shelfIdsWithMovie(ownerId: String, movieId: Long): List<Long> = transaction {
        val ownerDbId = userDbId(ownerId) ?: return@transaction emptyList()
        (ShelfItemsTable innerJoin ShelvesTable)
            .select(ShelvesTable.id)
            .where { (ShelvesTable.userId eq ownerDbId) and (ShelfItemsTable.movieId eq movieId) }
            .orderBy(ShelvesTable.position to SortOrder.ASC)
            .map { it[ShelvesTable.id].value }
    }

    override fun publicShelvesWithMovie(movieId: Long, page: PageRequest): Page<PublicShelf> = transaction {
        val query = (ShelvesTable innerJoin UsersTable).selectAll()
            .where {
                ShelvesTable.systemType.isNull() and (ShelvesTable.visibility eq Visibility.PUBLIC.wireValue) and
                    (ShelvesTable.id inSubQuery ShelfItemsTable.select(ShelfItemsTable.shelfId)
                        .where { ShelfItemsTable.movieId eq movieId })
            }
        val total = query.count().toInt()
        val shelves = query
            .orderBy(ShelvesTable.updatedAt to SortOrder.DESC, ShelvesTable.id to SortOrder.DESC)
            .limit(page.limit, offset = page.offset)
            .map { PublicShelf(toShelf(it), it.toUserSummary()) }
        Page.of(shelves, page, total)
    }

    override fun addMovie(shelf: Shelf, movieId: Long) {
        transaction {
            val ownerDbId = userDbId(shelf.ownerId) ?: return@transaction
            val now = Clock.System.now()
            val systemType = shelf.systemType
            if (systemType != null) {
                SystemShelves.setStatus(ownerDbId, movieId, SystemShelves.statusOf(systemType), now)
            } else if (SystemShelves.addItem(shelf.id, movieId, now)) {
                touch(shelf.id)
            }
        }
    }

    override fun removeMovie(shelf: Shelf, movieId: Long) {
        transaction {
            val ownerDbId = userDbId(shelf.ownerId) ?: return@transaction
            val systemType = shelf.systemType
            if (systemType != null) {
                SystemShelves.unsetStatus(ownerDbId, movieId, systemType, Clock.System.now())
            } else {
                SystemShelves.removeItem(shelf.id, movieId)
                touch(shelf.id)
            }
        }
    }

    override fun transferMovies(from: Shelf, to: Shelf, mode: ShelfTransferMode, movieIds: List<Long>) {
        transaction {
            val inShelf = ShelfItemsTable.selectAll()
                .where { ShelfItemsTable.shelfId eq from.id }
                .orderBy(ShelfItemsTable.position to SortOrder.ASC, ShelfItemsTable.id to SortOrder.ASC)
                .map { it[ShelfItemsTable.movieId].value }
            val selected = if (movieIds.isEmpty()) inShelf else inShelf.filter { it in movieIds.toSet() }
            for (movieId in selected) {
                addMovie(to, movieId)
                if (mode == ShelfTransferMode.MOVE) removeMovie(from, movieId)
            }
        }
    }

    override fun sortShelf(shelfId: Long, sortMode: ShelfSort, applyOnce: Boolean) {
        transaction {
            if (applyOnce) {
                val shelf = getShelf(shelfId) ?: return@transaction
                val ownerDbId = userDbId(shelf.ownerId) ?: return@transaction
                val ordered = shelfMoviesQuery(shelfId, ownerDbId, ShelfMovieFilter())
                    .orderBy(*orderOf(sortMode))
                    .map { it[ShelfItemsTable.movieId].value }
                writeMoviePositions(shelfId, ordered)
            }
            ShelvesTable.update({ ShelvesTable.id eq shelfId }) {
                it[ShelvesTable.sortMode] = (if (applyOnce) ShelfSort.MANUAL else sortMode).wireValue
                it[updatedAt] = Clock.System.now()
            }
        }
    }

    override fun reorderMovies(shelfId: Long, movieIds: List<Long>) {
        transaction {
            val current = ShelfItemsTable.selectAll()
                .where { ShelfItemsTable.shelfId eq shelfId }
                .orderBy(ShelfItemsTable.position to SortOrder.ASC, ShelfItemsTable.id to SortOrder.ASC)
                .map { it[ShelfItemsTable.movieId].value }
            writeMoviePositions(shelfId, movieIds.filter { it in current } + current.filterNot { it in movieIds })
            touch(shelfId)
        }
    }

    override fun reorderShelves(ownerId: String, shelfIds: List<Long>, pinnedIds: List<Long>?) {
        transaction {
            val ownerDbId = userDbId(ownerId) ?: return@transaction
            val current = ShelvesTable.selectAll()
                .where { ShelvesTable.userId eq ownerDbId }
                .orderBy(ShelvesTable.position to SortOrder.ASC, ShelvesTable.id to SortOrder.ASC)
                .map { it[ShelvesTable.id].value }
            val ordered = shelfIds.filter { it in current } + current.filterNot { it in shelfIds }
            ordered.forEachIndexed { index, id ->
                ShelvesTable.update({ ShelvesTable.id eq id }) {
                    it[position] = index
                    if (pinnedIds != null) it[pinned] = id in pinnedIds
                }
            }
        }
    }

    // ── Helpers (inside a transaction) ──────────────────────────────────────

    override fun isSaved(shelfId: Long, userId: String): Boolean = transaction {
        val userDbId = userDbId(userId) ?: return@transaction false
        ShelfSavesTable.selectAll()
            .where { (ShelfSavesTable.shelfId eq shelfId) and (ShelfSavesTable.userId eq userDbId) }
            .any()
    }

    override fun saveShelf(shelfId: Long, userId: String) {
        transaction {
            val userDbId = userDbId(userId) ?: return@transaction
            ShelfSavesTable.insertIgnore {
                it[ShelfSavesTable.shelfId] = shelfId
                it[ShelfSavesTable.userId] = userDbId
                it[createdAt] = Clock.System.now()
            }
        }
    }

    override fun unsaveShelf(shelfId: Long, userId: String) {
        transaction {
            val userDbId = userDbId(userId) ?: return@transaction
            ShelfSavesTable.deleteWhere {
                (ShelfSavesTable.shelfId eq shelfId) and (ShelfSavesTable.userId eq userDbId)
            }
        }
    }

    override fun savedShelves(userId: String, page: PageRequest): Page<Shelf> = transaction {
        val viewer = userDbId(userId) ?: return@transaction Page.of(emptyList(), page, 0)
        val ownerFollowsViewer = exists(
            UserFollowsTable.selectAll()
                .where { (UserFollowsTable.followerId eq ShelvesTable.userId) and (UserFollowsTable.followedId eq viewer) }
        )
        val viewerFollowsOwner = exists(
            UserFollowsTable.selectAll()
                .where { (UserFollowsTable.followerId eq viewer) and (UserFollowsTable.followedId eq ShelvesTable.userId) }
        )
        // The same rule as VisibilityPolicy: public, or mutual between people who follow each other.
        val visible = (ShelvesTable.visibility eq Visibility.PUBLIC.wireValue) or
            ((ShelvesTable.visibility eq Visibility.MUTUAL.wireValue) and ownerFollowsViewer and viewerFollowsOwner)
        val query = ShelfSavesTable
            .join(ShelvesTable, JoinType.INNER, ShelfSavesTable.shelfId, ShelvesTable.id)
            .join(UsersTable, JoinType.INNER, ShelvesTable.userId, UsersTable.id)
            .selectAll()
            .where { (ShelfSavesTable.userId eq viewer) and visible }
        val total = query.count().toInt()
        val shelves = query
            .orderBy(ShelfSavesTable.createdAt to SortOrder.DESC, ShelvesTable.id to SortOrder.DESC)
            .limit(page.limit, offset = page.offset)
            .map { toShelf(it).copy(saved = true) }
        Page.of(shelves, page, total)
    }

    override fun recordVisit(shelfId: Long, userId: String) {
        transaction {
            val userDbId = userDbId(userId) ?: return@transaction
            val now = Clock.System.now()
            val updated = ShelfVisitsTable.update({
                (ShelfVisitsTable.shelfId eq shelfId) and (ShelfVisitsTable.userId eq userDbId)
            }) { it[visitedAt] = now }
            if (updated == 0) {
                ShelfVisitsTable.insertIgnore {
                    it[ShelfVisitsTable.shelfId] = shelfId
                    it[ShelfVisitsTable.userId] = userDbId
                    it[visitedAt] = now
                }
            }
        }
    }

    private fun userDbId(userId: String): Long? = UsersTable.selectAll()
        .where { UsersTable.externalId eq userId }
        .firstOrNull()
        ?.get(UsersTable.id)?.value

    private fun touch(shelfId: Long) {
        ShelvesTable.update({ ShelvesTable.id eq shelfId }) { it[updatedAt] = Clock.System.now() }
    }

    private fun writeMoviePositions(shelfId: Long, orderedMovieIds: List<Long>) {
        orderedMovieIds.forEachIndexed { index, movieId ->
            ShelfItemsTable.update({ (ShelfItemsTable.shelfId eq shelfId) and (ShelfItemsTable.movieId eq movieId) }) {
                it[position] = index
            }
        }
    }

    private fun shelfMoviesQuery(shelfId: Long, ownerDbId: Long, filter: ShelfMovieFilter) =
        ShelfItemsTable
            .join(MoviesTable, JoinType.INNER, ShelfItemsTable.movieId, MoviesTable.id)
            .join(
                UserMoviesTable, JoinType.LEFT, ShelfItemsTable.movieId, UserMoviesTable.movieId,
                additionalConstraint = { UserMoviesTable.userId eq ownerDbId }
            )
            .selectAll()
            .where {
                var condition: Op<Boolean> = ShelfItemsTable.shelfId eq shelfId
                filter.watched?.let { watched ->
                    condition = condition and if (watched) {
                        UserMoviesTable.status eq SystemShelves.STATUS_WATCHED
                    } else {
                        UserMoviesTable.status.isNull() or (UserMoviesTable.status neq SystemShelves.STATUS_WATCHED)
                    }
                }
                filter.genreTmdbId?.let { genre ->
                    condition = condition and (MoviesTable.id inSubQuery (MovieGenresTable innerJoin GenresTable)
                        .select(MovieGenresTable.movieId)
                        .where { GenresTable.tmdbId eq genre })
                }
                filter.decade?.let { decade -> condition = condition and MoviesTable.year.between(decade, decade + 9) }
                filter.rating?.let { rating -> condition = condition and (UserMoviesTable.rating eq rating.wireValue) }
                condition
            }

    private fun orderOf(sortMode: ShelfSort): Array<Pair<Expression<*>, SortOrder>> {
        val watchedFirst = Case()
            .When(UserMoviesTable.status eq SystemShelves.STATUS_WATCHED, intLiteral(0))
            .Else(intLiteral(1))
        // "Por avaliação": the order of the vocabulary, unrated last (B13.4).
        val first = Rating.entries.first()
        val ratingOrder = Rating.entries.drop(1)
            .fold(Case().When(UserMoviesTable.rating eq first.wireValue, intLiteral(first.ordinal))) { case, rating ->
                case.When(UserMoviesTable.rating eq rating.wireValue, intLiteral(rating.ordinal))
            }
            .Else(intLiteral(Rating.entries.size))
        val primary: List<Pair<Expression<*>, SortOrder>> = when (sortMode) {
            ShelfSort.MANUAL -> emptyList()
            ShelfSort.TITLE -> listOf(MoviesTable.title to SortOrder.ASC)
            ShelfSort.DIRECTOR -> listOf(MoviesTable.director to SortOrder.ASC_NULLS_LAST, MoviesTable.title to SortOrder.ASC)
            ShelfSort.RELEASE_YEAR -> listOf(MoviesTable.year to SortOrder.ASC_NULLS_LAST)
            ShelfSort.ADDED_NEWEST -> listOf(ShelfItemsTable.addedAt to SortOrder.DESC)
            ShelfSort.ADDED_OLDEST -> listOf(ShelfItemsTable.addedAt to SortOrder.ASC)
            ShelfSort.UPDATED_NEWEST -> listOf(UserMoviesTable.updatedAt to SortOrder.DESC_NULLS_LAST)
            ShelfSort.WATCHED_FIRST -> listOf(watchedFirst to SortOrder.ASC)
            // Progress of the current cycle, summarized from the watch logs (B2); watched breaks ties.
            ShelfSort.PROGRESS_HIGHEST ->
                listOf(UserMoviesTable.progressPercent to SortOrder.DESC_NULLS_LAST, watchedFirst to SortOrder.ASC)
            ShelfSort.WATCHED_LAST -> listOf(watchedFirst to SortOrder.DESC)
            ShelfSort.RATING_HIGHEST -> listOf(ratingOrder to SortOrder.ASC)
        }
        return (primary + listOf(ShelfItemsTable.position to SortOrder.ASC, ShelfItemsTable.id to SortOrder.ASC))
            .toTypedArray()
    }

    private fun toShelf(row: ResultRow): Shelf {
        val shelfId = row[ShelvesTable.id].value
        val ownerDbId = row[ShelvesTable.userId].value
        val totalMovies = ShelfItemsTable.selectAll().where { ShelfItemsTable.shelfId eq shelfId }.count().toInt()
        val totalWatched = ShelfItemsTable
            .join(
                UserMoviesTable, JoinType.INNER, ShelfItemsTable.movieId, UserMoviesTable.movieId,
                additionalConstraint = { UserMoviesTable.userId eq ownerDbId }
            )
            .selectAll()
            .where { (ShelfItemsTable.shelfId eq shelfId) and (UserMoviesTable.status eq SystemShelves.STATUS_WATCHED) }
            .count().toInt()
        val sortMode = ShelfSort.fromWireValue(row[ShelvesTable.sortMode]) ?: ShelfSort.DEFAULT
        val posterPaths = ShelfItemsTable
            .join(MoviesTable, JoinType.INNER, ShelfItemsTable.movieId, MoviesTable.id)
            .select(MoviesTable.posterPath)
            .where { (ShelfItemsTable.shelfId eq shelfId) and MoviesTable.posterPath.isNotNull() }
            .orderBy(ShelfItemsTable.position to SortOrder.ASC)
            .limit(4)
            .mapNotNull { it[MoviesTable.posterPath] }
        return Shelf(
            id = shelfId,
            ownerId = row[UsersTable.externalId],
            name = row[ShelvesTable.name],
            description = row[ShelvesTable.description],
            color = row[ShelvesTable.color],
            visibility = Visibility.fromWireValue(row[ShelvesTable.visibility]) ?: Visibility.DEFAULT,
            systemType = row[ShelvesTable.systemType]?.let { SystemShelfType.fromWireValue(it) },
            sortMode = sortMode,
            position = row[ShelvesTable.position],
            pinned = row[ShelvesTable.pinned],
            coverUrl = row[ShelvesTable.coverUrl],
            totalMovies = totalMovies,
            totalWatched = totalWatched,
            posterPaths = posterPaths,
            createdAt = row[ShelvesTable.createdAt],
            updatedAt = row[ShelvesTable.updatedAt],
            owner = row.toUserSummary(),
            saves = ShelfSavesTable.selectAll().where { ShelfSavesTable.shelfId eq shelfId }.count().toInt()
        )
    }

    private fun toShelfMovie(row: ResultRow) = ShelfMovie(
        movieId = row[ShelfItemsTable.movieId].value,
        tmdbId = row[MoviesTable.tmdbId],
        title = row[MoviesTable.localTitle] ?: row[MoviesTable.title],
        posterPath = row[MoviesTable.posterPath],
        year = row[MoviesTable.year],
        director = row[MoviesTable.director],
        runtime = row[MoviesTable.runtime],
        position = row[ShelfItemsTable.position],
        addedAt = row[ShelfItemsTable.addedAt],
        watched = row.getOrNull(UserMoviesTable.status) == SystemShelves.STATUS_WATCHED,
        rating = row.getOrNull(UserMoviesTable.rating)?.let { Rating.fromWireValue(it) }
    )

    private fun escapeLike(value: String): String =
        value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
}
