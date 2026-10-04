package org.mobyle.data.local.movies

import kotlinx.datetime.Clock
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.transactions.transaction
import org.mobyle.data.local.database.ReviewLikesTable

interface ReviewLikesDataSource {
    fun like(userExternalId: String, reviewId: String)
    fun unlike(userExternalId: String, reviewId: String)
    fun getLikeCount(reviewId: String): Int
    fun isLikedByUser(userExternalId: String, reviewId: String): Boolean
    fun getLikeCounts(reviewIds: List<String>): Map<String, Int>
    fun getLikedReviewIds(userExternalId: String, reviewIds: List<String>): Set<String>
}

class ReviewLikesDataSourceImpl : ReviewLikesDataSource {

    override fun like(userExternalId: String, reviewId: String) {
        transaction {
            val exists = ReviewLikesTable.selectAll()
                .where {
                    (ReviewLikesTable.userExternalId eq userExternalId) and
                        (ReviewLikesTable.reviewId eq reviewId)
                }
                .firstOrNull()
            if (exists == null) {
                ReviewLikesTable.insert {
                    it[ReviewLikesTable.userExternalId] = userExternalId
                    it[ReviewLikesTable.reviewId] = reviewId
                    it[createdAt] = Clock.System.now()
                }
            }
        }
    }

    override fun unlike(userExternalId: String, reviewId: String) {
        transaction {
            ReviewLikesTable.deleteWhere {
                (ReviewLikesTable.userExternalId eq userExternalId) and
                    (ReviewLikesTable.reviewId eq reviewId)
            }
        }
    }

    override fun getLikeCount(reviewId: String): Int {
        return transaction {
            ReviewLikesTable.selectAll()
                .where { ReviewLikesTable.reviewId eq reviewId }
                .count()
                .toInt()
        }
    }

    override fun isLikedByUser(userExternalId: String, reviewId: String): Boolean {
        return transaction {
            ReviewLikesTable.selectAll()
                .where {
                    (ReviewLikesTable.userExternalId eq userExternalId) and
                        (ReviewLikesTable.reviewId eq reviewId)
                }
                .firstOrNull() != null
        }
    }

    override fun getLikeCounts(reviewIds: List<String>): Map<String, Int> {
        if (reviewIds.isEmpty()) return emptyMap()
        return transaction {
            ReviewLikesTable.selectAll()
                .where { ReviewLikesTable.reviewId inList reviewIds }
                .groupBy { it[ReviewLikesTable.reviewId] }
                .mapValues { (_, rows) -> rows.size }
        }
    }

    override fun getLikedReviewIds(userExternalId: String, reviewIds: List<String>): Set<String> {
        if (reviewIds.isEmpty()) return emptySet()
        return transaction {
            ReviewLikesTable.selectAll()
                .where {
                    (ReviewLikesTable.userExternalId eq userExternalId) and
                        (ReviewLikesTable.reviewId inList reviewIds)
                }
                .map { it[ReviewLikesTable.reviewId] }
                .toSet()
        }
    }
}
