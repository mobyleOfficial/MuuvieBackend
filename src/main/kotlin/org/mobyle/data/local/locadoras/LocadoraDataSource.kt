package org.mobyle.data.local.locadoras

import kotlinx.datetime.Clock
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.notInList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.countDistinct
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.max
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.statements.UpdateBuilder
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.mobyle.data.local.database.LocadoraShelvesTable
import org.mobyle.data.local.database.LocadorasTable
import org.mobyle.data.local.database.MoviesTable
import org.mobyle.data.local.database.ShelfItemsTable
import org.mobyle.data.local.database.ShelvesTable
import org.mobyle.data.local.database.UserMoviesTable
import org.mobyle.data.local.database.UsersTable
import org.mobyle.data.local.shelves.ShelfDataSource
import org.mobyle.data.local.shelves.SystemShelves
import org.mobyle.domain.model.Locadora
import org.mobyle.domain.model.LocadoraDraft
import org.mobyle.domain.model.LocadoraShelf
import org.mobyle.domain.model.Page
import org.mobyle.domain.model.PageRequest
import org.mobyle.domain.model.ShelfMovieFilter
import org.mobyle.domain.model.vocabulary.Visibility

/**
 * Locadoras and the shelves they group (B4). Users are identified by their external id.
 * [shelfVisibilities] is what the viewer may see of the owner's shelves: shelves outside
 * it are left out of the listings and of the counters.
 */
interface LocadoraDataSource {
    fun getLocadoraOwnerId(locadoraId: Long): String?
    fun getLocadora(locadoraId: Long, shelfVisibilities: Set<Visibility>): Locadora?
    fun getUserLocadoras(ownerId: String, visibilities: Set<Visibility>, page: PageRequest): Page<Locadora>
    fun createLocadora(ownerId: String, draft: LocadoraDraft): Locadora
    fun updateLocadora(locadoraId: Long, draft: LocadoraDraft): Locadora
    fun deleteLocadora(locadoraId: Long)
    fun getLocadoraShelves(
        locadoraId: Long,
        shelfVisibilities: Set<Visibility>,
        page: PageRequest,
        moviesPerShelf: Int
    ): Page<LocadoraShelf>
    fun locadoraShelfIds(locadoraId: Long): List<Long>
    fun setShelves(locadoraId: Long, shelfIds: List<Long>)
    fun reorderShelves(locadoraId: Long, shelfIds: List<Long>)
    fun reorderLocadoras(ownerId: String, locadoraIds: List<Long>, pinnedIds: List<Long>?)
}

class LocadoraDataSourceImpl(private val shelves: ShelfDataSource) : LocadoraDataSource {

    override fun getLocadoraOwnerId(locadoraId: Long): String? = transaction {
        (LocadorasTable innerJoin UsersTable).select(UsersTable.externalId)
            .where { LocadorasTable.id eq locadoraId }
            .firstOrNull()
            ?.get(UsersTable.externalId)
    }

    override fun getLocadora(locadoraId: Long, shelfVisibilities: Set<Visibility>): Locadora? = transaction {
        (LocadorasTable innerJoin UsersTable).selectAll()
            .where { LocadorasTable.id eq locadoraId }
            .firstOrNull()
            ?.let { toLocadora(it, shelfVisibilities) }
    }

    override fun getUserLocadoras(ownerId: String, visibilities: Set<Visibility>, page: PageRequest): Page<Locadora> =
        transaction {
            val ownerDbId = userDbId(ownerId) ?: return@transaction Page.of(emptyList(), page, 0)
            val query = (LocadorasTable innerJoin UsersTable).selectAll()
                .where {
                    (LocadorasTable.userId eq ownerDbId) and
                        (LocadorasTable.visibility inList visibilities.map { it.wireValue })
                }
            val total = query.count().toInt()
            val locadoras = query
                .orderBy(
                    LocadorasTable.pinned to SortOrder.DESC,
                    LocadorasTable.position to SortOrder.ASC,
                    LocadorasTable.id to SortOrder.ASC
                )
                .limit(page.limit, offset = page.offset)
                .map { toLocadora(it, visibilities) }
            Page.of(locadoras, page, total)
        }

    override fun createLocadora(ownerId: String, draft: LocadoraDraft): Locadora = transaction {
        val ownerDbId = userDbId(ownerId) ?: throw IllegalStateException("User not found")
        val now = Clock.System.now()
        val maxPosition = LocadorasTable.select(LocadorasTable.position.max())
            .where { LocadorasTable.userId eq ownerDbId }
            .firstOrNull()
            ?.get(LocadorasTable.position.max()) ?: -1
        val locadoraId = LocadorasTable.insertAndGetId {
            it[userId] = ownerDbId
            it[position] = maxPosition + 1
            it[createdAt] = now
            it[updatedAt] = now
            writeDraft(it, draft)
        }.value
        getLocadora(locadoraId, Visibility.entries.toSet())!!
    }

    override fun updateLocadora(locadoraId: Long, draft: LocadoraDraft): Locadora = transaction {
        LocadorasTable.update({ LocadorasTable.id eq locadoraId }) {
            writeDraft(it, draft)
            it[updatedAt] = Clock.System.now()
        }
        getLocadora(locadoraId, Visibility.entries.toSet())!!
    }

    override fun deleteLocadora(locadoraId: Long) {
        transaction {
            // Only the grouping goes away; the shelves stay.
            LocadoraShelvesTable.deleteWhere { LocadoraShelvesTable.locadoraId eq locadoraId }
            LocadorasTable.deleteWhere { LocadorasTable.id eq locadoraId }
        }
    }

    override fun getLocadoraShelves(
        locadoraId: Long,
        shelfVisibilities: Set<Visibility>,
        page: PageRequest,
        moviesPerShelf: Int
    ): Page<LocadoraShelf> = transaction {
        val query = visibleShelvesQuery(locadoraId, shelfVisibilities)
        val total = query.count().toInt()
        val preview = PageRequest(1, moviesPerShelf)
        val results = query
            .orderBy(LocadoraShelvesTable.position to SortOrder.ASC, LocadoraShelvesTable.id to SortOrder.ASC)
            .limit(page.limit, offset = page.offset)
            .mapNotNull { row -> shelves.getShelf(row[LocadoraShelvesTable.shelfId].value) }
            .map { shelf -> LocadoraShelf(shelf, shelves.getShelfMovies(shelf, ShelfMovieFilter(), preview).results) }
        Page.of(results, page, total)
    }

    override fun locadoraShelfIds(locadoraId: Long): List<Long> = transaction { orderedShelfIds(locadoraId) }

    override fun setShelves(locadoraId: Long, shelfIds: List<Long>) {
        transaction {
            val current = orderedShelfIds(locadoraId)
            LocadoraShelvesTable.deleteWhere {
                (LocadoraShelvesTable.locadoraId eq locadoraId) and (LocadoraShelvesTable.shelfId notInList shelfIds)
            }
            // Shelves already in keep their order; new ones go to the end, in the given order.
            val now = Clock.System.now()
            val kept = current.filter { it in shelfIds }
            shelfIds.filterNot { it in current }.forEachIndexed { index, shelfId ->
                LocadoraShelvesTable.insert {
                    it[LocadoraShelvesTable.locadoraId] = locadoraId
                    it[LocadoraShelvesTable.shelfId] = shelfId
                    it[position] = kept.size + index
                    it[addedAt] = now
                }
            }
            writeShelfPositions(locadoraId, kept)
            touch(locadoraId)
        }
    }

    override fun reorderShelves(locadoraId: Long, shelfIds: List<Long>) {
        transaction {
            val current = orderedShelfIds(locadoraId)
            writeShelfPositions(locadoraId, shelfIds.filter { it in current } + current.filterNot { it in shelfIds })
            touch(locadoraId)
        }
    }

    override fun reorderLocadoras(ownerId: String, locadoraIds: List<Long>, pinnedIds: List<Long>?) {
        transaction {
            val ownerDbId = userDbId(ownerId) ?: return@transaction
            val current = LocadorasTable.selectAll()
                .where { LocadorasTable.userId eq ownerDbId }
                .orderBy(LocadorasTable.position to SortOrder.ASC, LocadorasTable.id to SortOrder.ASC)
                .map { it[LocadorasTable.id].value }
            val ordered = locadoraIds.filter { it in current } + current.filterNot { it in locadoraIds }
            ordered.forEachIndexed { index, id ->
                LocadorasTable.update({ LocadorasTable.id eq id }) {
                    it[position] = index
                    if (pinnedIds != null) it[pinned] = id in pinnedIds
                }
            }
        }
    }

    // ── Helpers (inside a transaction) ──────────────────────────────────────

    private fun userDbId(userId: String): Long? = UsersTable.selectAll()
        .where { UsersTable.externalId eq userId }
        .firstOrNull()
        ?.get(UsersTable.id)?.value

    private fun writeDraft(statement: UpdateBuilder<*>, draft: LocadoraDraft) {
        statement[LocadorasTable.name] = draft.name
        statement[LocadorasTable.description] = draft.description
        statement[LocadorasTable.visibility] = draft.visibility.wireValue
        statement[LocadorasTable.hideShelvesInProfile] = draft.hideShelvesInProfile
        statement[LocadorasTable.hideShelvesInFilters] = draft.hideShelvesInFilters
    }

    private fun touch(locadoraId: Long) {
        LocadorasTable.update({ LocadorasTable.id eq locadoraId }) { it[updatedAt] = Clock.System.now() }
    }

    private fun orderedShelfIds(locadoraId: Long): List<Long> = LocadoraShelvesTable.selectAll()
        .where { LocadoraShelvesTable.locadoraId eq locadoraId }
        .orderBy(LocadoraShelvesTable.position to SortOrder.ASC, LocadoraShelvesTable.id to SortOrder.ASC)
        .map { it[LocadoraShelvesTable.shelfId].value }

    private fun writeShelfPositions(locadoraId: Long, orderedShelfIds: List<Long>) {
        orderedShelfIds.forEachIndexed { index, shelfId ->
            LocadoraShelvesTable.update({
                (LocadoraShelvesTable.locadoraId eq locadoraId) and (LocadoraShelvesTable.shelfId eq shelfId)
            }) { it[position] = index }
        }
    }

    private fun visibleShelvesQuery(locadoraId: Long, shelfVisibilities: Set<Visibility>) =
        (LocadoraShelvesTable innerJoin ShelvesTable).selectAll()
            .where { (LocadoraShelvesTable.locadoraId eq locadoraId) and visibleShelf(shelfVisibilities) }

    private fun visibleShelf(shelfVisibilities: Set<Visibility>): Op<Boolean> =
        ShelvesTable.visibility inList shelfVisibilities.map { it.wireValue }

    private fun toLocadora(row: ResultRow, shelfVisibilities: Set<Visibility>): Locadora {
        val locadoraId = row[LocadorasTable.id].value
        val ownerDbId = row[LocadorasTable.userId].value
        val totalShelves = visibleShelvesQuery(locadoraId, shelfVisibilities).count().toInt()
        val items = LocadoraShelvesTable
            .join(ShelvesTable, JoinType.INNER, LocadoraShelvesTable.shelfId, ShelvesTable.id)
            .join(ShelfItemsTable, JoinType.INNER, LocadoraShelvesTable.shelfId, ShelfItemsTable.shelfId)
        val inLocadora = (LocadoraShelvesTable.locadoraId eq locadoraId) and visibleShelf(shelfVisibilities)
        val distinctMovies = ShelfItemsTable.movieId.countDistinct()
        val totalMovies = items.select(distinctMovies).where { inLocadora }.single()[distinctMovies].toInt()
        val totalWatched = items
            .join(
                UserMoviesTable, JoinType.INNER, ShelfItemsTable.movieId, UserMoviesTable.movieId,
                additionalConstraint = { UserMoviesTable.userId eq ownerDbId }
            )
            .select(distinctMovies)
            .where { inLocadora and (UserMoviesTable.status eq SystemShelves.STATUS_WATCHED) }
            .single()[distinctMovies].toInt()
        val posterPaths = items
            .join(MoviesTable, JoinType.INNER, ShelfItemsTable.movieId, MoviesTable.id)
            .select(MoviesTable.posterPath)
            .where { inLocadora and MoviesTable.posterPath.isNotNull() }
            .orderBy(LocadoraShelvesTable.position to SortOrder.ASC, ShelfItemsTable.position to SortOrder.ASC)
            .limit(POSTER_SCAN_LIMIT)
            .mapNotNull { it[MoviesTable.posterPath] }
            .distinct()
            .take(POSTER_COUNT)
        return Locadora(
            id = locadoraId,
            ownerId = row[UsersTable.externalId],
            ownerUsername = row[UsersTable.username],
            ownerAvatarUrl = row[UsersTable.avatarUrl],
            name = row[LocadorasTable.name],
            description = row[LocadorasTable.description],
            visibility = Visibility.fromWireValue(row[LocadorasTable.visibility]) ?: Visibility.DEFAULT,
            hideShelvesInProfile = row[LocadorasTable.hideShelvesInProfile],
            hideShelvesInFilters = row[LocadorasTable.hideShelvesInFilters],
            position = row[LocadorasTable.position],
            pinned = row[LocadorasTable.pinned],
            coverUrl = row[LocadorasTable.coverUrl],
            totalShelves = totalShelves,
            totalMovies = totalMovies,
            totalWatched = totalWatched,
            posterPaths = posterPaths,
            createdAt = row[LocadorasTable.createdAt],
            updatedAt = row[LocadorasTable.updatedAt]
        )
    }

    private companion object {
        const val POSTER_COUNT = 4
        // The same movie can sit in several shelves; read a few more rows to find 4 distinct posters.
        const val POSTER_SCAN_LIMIT = 20
    }
}
