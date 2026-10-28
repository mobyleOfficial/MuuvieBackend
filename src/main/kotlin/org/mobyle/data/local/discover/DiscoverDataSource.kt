package org.mobyle.data.local.discover

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import org.jetbrains.exposed.sql.Expression
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.Query
import org.jetbrains.exposed.sql.QueryBuilder
import org.jetbrains.exposed.sql.EqOp
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inSubQuery
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.notInSubQuery
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.countDistinct
import org.jetbrains.exposed.sql.exists
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.wrapAsExpression
import org.mobyle.data.local.database.ActivitiesTable
import org.mobyle.data.local.database.ActivityLovesTable
import org.mobyle.data.local.database.LocadoraShelvesTable
import org.mobyle.data.local.database.LocadorasTable
import org.mobyle.data.local.database.ShelfItemsTable
import org.mobyle.data.local.database.ShelfSavesTable
import org.mobyle.data.local.database.ShelfVisitsTable
import org.mobyle.data.local.database.UserFollowsTable
import org.mobyle.data.local.database.ShelvesTable
import org.mobyle.data.local.database.UserMoviesTable
import org.mobyle.data.local.database.UsersTable
import org.mobyle.data.local.database.WatchLogsTable
import org.mobyle.domain.model.Page
import org.mobyle.domain.model.PageRequest
import org.mobyle.domain.model.vocabulary.Rating
import org.mobyle.domain.model.vocabulary.SystemShelfType
import org.mobyle.domain.model.vocabulary.Visibility
import org.mobyle.domain.model.vocabulary.ActivitySubject

/**
 * Ranking of the discovery hub (F5.6): curated owners first (`users.featured`), then activity
 * since [since] (movies added to shelves, public sessions), then the most recently updated.
 * Only public items show up; [excludeUserId] leaves the viewer's own items out, except when a
 * query searches by name: then the viewer's own items match too, whatever their visibility.
 * Answers ids only; the repository loads them with the usual mappers.
 */
interface DiscoverDataSource {
    fun shelfIds(excludeUserId: String?, query: String?, since: Instant, page: PageRequest): Page<Long>
    fun locadoraIds(excludeUserId: String?, query: String?, since: Instant, page: PageRequest): Page<Long>
    /** [query] matches the username, the @handle or the display name (F14.2). */
    fun userIds(excludeUserId: String?, query: String?, since: Instant, page: PageRequest): Page<String>

    /** "Em alta no Muuvie" (B14.1): movies by distinct people with a public session on or after [since]. */
    fun trendingMovieIds(since: LocalDate, page: PageRequest): Page<Long>

    /**
     * "Mais amados no Muuvie" (B14.1): movies by Favoritado + Adorei labels, counting only people
     * whose Já assisti is public, as the movie's Comunidade does; below [minRatings] they stay out.
     */
    fun lovedMovieIds(minRatings: Int, page: PageRequest): Page<Long>
}

class DiscoverDataSourceImpl : DiscoverDataSource {

    private val public = Visibility.PUBLIC.wireValue

    override fun shelfIds(excludeUserId: String?, query: String?, since: Instant, page: PageRequest): Page<Long> =
        transaction {
            val recentItems = wrapAsExpression<Long>(
                ShelfItemsTable.select(ShelfItemsTable.id.count())
                    .where { (ShelfItemsTable.shelfId eq ShelvesTable.id) and (ShelfItemsTable.addedAt greaterEq since) }
            )
            val hasMovies = exists(
                ShelfItemsTable.select(ShelfItemsTable.id).where { ShelfItemsTable.shelfId eq ShelvesTable.id }
            )
            // "Ocultar estantes dos filtros" (F4.1) also keeps the shelves out of discovery.
            val notHidden = ShelvesTable.id notInSubQuery (LocadoraShelvesTable innerJoin LocadorasTable)
                .select(LocadoraShelvesTable.shelfId)
                .where { LocadorasTable.hideShelvesInFilters eq true }
            val discoverable = (ShelvesTable.visibility eq public) and hasMovies and notHidden and notOwnedBy(excludeUserId)
            val condition = ShelvesTable.systemType.isNull() and nameMatches(ShelvesTable.name, query) and
                orOwnWhenSearching(discoverable, excludeUserId, query)
            // "Estantes mais populares" (B14.4): no curated-first here, popularity decides.
            (ShelvesTable innerJoin UsersTable).select(ShelvesTable.id)
                .where { condition }
                .page(page, listOf(shelfPopularity(since), recentItems), ShelvesTable.updatedAt, ShelvesTable.id) {
                    it[ShelvesTable.id].value
                }
        }

    override fun locadoraIds(excludeUserId: String?, query: String?, since: Instant, page: PageRequest): Page<Long> =
        transaction {
            val publicShelvesOfLocadora = LocadoraShelvesTable
                .join(ShelvesTable, JoinType.INNER, LocadoraShelvesTable.shelfId, ShelvesTable.id)
            val recentItems = wrapAsExpression<Long>(
                publicShelvesOfLocadora
                    .join(ShelfItemsTable, JoinType.INNER, ShelvesTable.id, ShelfItemsTable.shelfId)
                    .select(ShelfItemsTable.id.count())
                    .where {
                        (LocadoraShelvesTable.locadoraId eq LocadorasTable.id) and (ShelvesTable.visibility eq public) and
                            (ShelfItemsTable.addedAt greaterEq since)
                    }
            )
            val hasPublicShelf = exists(
                publicShelvesOfLocadora.select(LocadoraShelvesTable.id)
                    .where { (LocadoraShelvesTable.locadoraId eq LocadorasTable.id) and (ShelvesTable.visibility eq public) }
            )
            val discoverable = (LocadorasTable.visibility eq public) and hasPublicShelf and notOwnedBy(excludeUserId)
            val condition = nameMatches(LocadorasTable.name, query) and
                orOwnWhenSearching(discoverable, excludeUserId, query)
            (LocadorasTable innerJoin UsersTable).select(LocadorasTable.id)
                .where { condition }
                .page(page, listOf(UsersTable.featured, recentItems), LocadorasTable.updatedAt, LocadorasTable.id) {
                    it[LocadorasTable.id].value
                }
        }

    override fun userIds(excludeUserId: String?, query: String?, since: Instant, page: PageRequest): Page<String> = transaction {
        val recentLogs = wrapAsExpression<Long>(
            WatchLogsTable.select(WatchLogsTable.id.count())
                .where {
                    (WatchLogsTable.userId eq UsersTable.id) and (WatchLogsTable.visibility eq public) and
                        (WatchLogsTable.createdAt greaterEq since)
                }
        )
        val recentItems = wrapAsExpression<Long>(
            ShelfItemsTable.join(ShelvesTable, JoinType.INNER, ShelfItemsTable.shelfId, ShelvesTable.id)
                .select(ShelfItemsTable.id.count())
                .where {
                    (ShelvesTable.userId eq UsersTable.id) and (ShelvesTable.visibility eq public) and
                        (ShelfItemsTable.addedAt greaterEq since)
                }
        )
        UsersTable.select(UsersTable.externalId)
            .where {
                orOwnWhenSearching(notOwnedBy(excludeUserId), excludeUserId, query) and (
                    nameMatches(UsersTable.username, query) or nameMatches(UsersTable.handle, query) or
                        nameMatches(UsersTable.displayName, query)
                    )
            }
            .page(page, listOf(UsersTable.featured, recentLogs, recentItems), UsersTable.createdAt, UsersTable.id) {
                it[UsersTable.externalId]
            }
    }

    override fun trendingMovieIds(since: LocalDate, page: PageRequest): Page<Long> = transaction {
        val people = WatchLogsTable.userId.countDistinct()
        WatchLogsTable.select(WatchLogsTable.movieId, people)
            .where { (WatchLogsTable.visibility eq public) and (WatchLogsTable.watchedOn greaterEq since) }
            .groupBy(WatchLogsTable.movieId)
            .rankedPage(page, people, WatchLogsTable.movieId) { it[WatchLogsTable.movieId].value }
    }

    override fun lovedMovieIds(minRatings: Int, page: PageRequest): Page<Long> = transaction {
        val publicWatchers = ShelvesTable.select(ShelvesTable.userId).where {
            (ShelvesTable.systemType eq SystemShelfType.WATCHED.wireValue) and (ShelvesTable.visibility eq public)
        }
        val labels = UserMoviesTable.id.count()
        UserMoviesTable.select(UserMoviesTable.movieId, labels)
            .where {
                (UserMoviesTable.rating inList listOf(Rating.FAVORITE.wireValue, Rating.LOVED.wireValue)) and
                    (UserMoviesTable.userId inSubQuery publicWatchers)
            }
            .groupBy(UserMoviesTable.movieId)
            .having { labels greaterEq minRatings.toLong() }
            .rankedPage(page, labels, UserMoviesTable.movieId) { it[UserMoviesTable.movieId].value }
    }

    // ── Helpers (inside a transaction) ──────────────────────────────────────

    /**
     * Popularity of the shelf of the current row (B14.4): 3 per save, 2 per person who gave an
     * "amei" to a public activity about it, 1 per visitor since [since] and 1 per follower of the
     * owner. The owner never counts.
     */
    private fun shelfPopularity(since: Instant): Expression<Long> {
        val saves = wrapAsExpression<Long>(
            ShelfSavesTable.select(ShelfSavesTable.userId.count())
                .where { (ShelfSavesTable.shelfId eq ShelvesTable.id) and (ShelfSavesTable.userId neq ShelvesTable.userId) }
        )
        val lovers = wrapAsExpression<Long>(
            ActivityLovesTable.join(ActivitiesTable, JoinType.INNER, ActivityLovesTable.activityId, ActivitiesTable.id)
                .select(ActivityLovesTable.userId.countDistinct())
                .where {
                    (ActivitiesTable.subjectType eq ActivitySubject.SHELF.wireValue) and
                        EqOp(ActivitiesTable.subjectId, ShelvesTable.id) and
                        (ActivitiesTable.visibility eq public) and (ActivitiesTable.hidden eq false) and
                        (ActivityLovesTable.userId neq ShelvesTable.userId)
                }
        )
        val visitors = wrapAsExpression<Long>(
            ShelfVisitsTable.select(ShelfVisitsTable.userId.count())
                .where {
                    (ShelfVisitsTable.shelfId eq ShelvesTable.id) and (ShelfVisitsTable.userId neq ShelvesTable.userId) and
                        (ShelfVisitsTable.visitedAt greaterEq since)
                }
        )
        val followers = wrapAsExpression<Long>(
            UserFollowsTable.select(UserFollowsTable.id.count())
                .where { (UserFollowsTable.followedId eq ShelvesTable.userId) and (UserFollowsTable.followerId neq ShelvesTable.userId) }
        )
        return WeightedSum(listOf(3 to saves, 2 to lovers, 1 to visitors, 1 to followers))
    }

    /** A grouped query ordered by [score], the higher first; ties go to the newest [id]. */
    private fun <T> Query.rankedPage(
        page: PageRequest,
        score: Expression<*>,
        id: Expression<*>,
        map: (ResultRow) -> T
    ): Page<T> {
        val total = count().toInt()
        val results = orderBy(score to SortOrder.DESC, id to SortOrder.DESC)
            .limit(page.limit, offset = page.offset)
            .map(map)
        return Page.of(results, page, total)
    }

    private fun notOwnedBy(userId: String?): Op<Boolean> =
        if (userId == null) Op.TRUE else UsersTable.externalId neq userId

    /**
     * The hub shows only what others made public, but the search of the Catálogo (F14.2) also
     * finds the viewer's own items, whatever their visibility.
     */
    private fun orOwnWhenSearching(discoverable: Op<Boolean>, viewerId: String?, query: String?): Op<Boolean> =
        if (viewerId == null || query.isNullOrBlank()) discoverable
        else discoverable or (UsersTable.externalId eq viewerId)

    private fun <T : String?> nameMatches(name: Expression<T>, query: String?): Op<Boolean> {
        val term = query?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return Op.TRUE
        return name.lowerCase() like "%${escapeLike(term)}%"
    }

    /** Ordered by each of [ranking], the higher first, then the most recently [updated] and the newest [id]. */
    private fun <T> Query.page(
        page: PageRequest,
        ranking: List<Expression<*>>,
        updated: Expression<*>,
        id: Expression<*>,
        map: (ResultRow) -> T
    ): Page<T> {
        val total = count().toInt()
        val order = ranking.map { it to SortOrder.DESC } + listOf(updated to SortOrder.DESC, id to SortOrder.DESC)
        val results = orderBy(*order.toTypedArray())
            .limit(page.limit, offset = page.offset)
            .map(map)
        return Page.of(results, page, total)
    }

    private fun escapeLike(value: String): String =
        value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
}

/** `(w1 * term1 + w2 * term2 ...)`, for scores made of counting subqueries. */
private class WeightedSum(private val terms: List<Pair<Int, Expression<*>>>) : Expression<Long>() {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) = queryBuilder {
        +"("
        terms.forEachIndexed { index, (weight, term) ->
            if (index > 0) +" + "
            +"$weight * "
            +term
        }
        +")"
    }
}
