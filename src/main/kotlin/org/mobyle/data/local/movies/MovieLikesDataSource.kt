package org.mobyle.data.local.movies

import kotlinx.datetime.Clock
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.transactions.transaction
import org.mobyle.data.local.database.MovieLikesTable

interface MovieLikesDataSource {
    fun like(userExternalId: String, movieId: Long)
    fun unlike(userExternalId: String, movieId: Long)
    fun getLikeCount(movieId: Long): Int
    fun isLikedByUser(userExternalId: String, movieId: Long): Boolean
}

class MovieLikesDataSourceImpl : MovieLikesDataSource {

    override fun like(userExternalId: String, movieId: Long) {
        transaction {
            val exists = MovieLikesTable.selectAll()
                .where {
                    (MovieLikesTable.userExternalId eq userExternalId) and
                        (MovieLikesTable.movieId eq movieId)
                }
                .firstOrNull()
            if (exists == null) {
                MovieLikesTable.insert {
                    it[MovieLikesTable.userExternalId] = userExternalId
                    it[MovieLikesTable.movieId] = movieId
                    it[createdAt] = Clock.System.now()
                }
            }
        }
    }

    override fun unlike(userExternalId: String, movieId: Long) {
        transaction {
            MovieLikesTable.deleteWhere {
                (MovieLikesTable.userExternalId eq userExternalId) and
                    (MovieLikesTable.movieId eq movieId)
            }
        }
    }

    override fun getLikeCount(movieId: Long): Int {
        return transaction {
            MovieLikesTable.selectAll()
                .where { MovieLikesTable.movieId eq movieId }
                .count()
                .toInt()
        }
    }

    override fun isLikedByUser(userExternalId: String, movieId: Long): Boolean {
        return transaction {
            MovieLikesTable.selectAll()
                .where {
                    (MovieLikesTable.userExternalId eq userExternalId) and
                        (MovieLikesTable.movieId eq movieId)
                }
                .firstOrNull() != null
        }
    }
}
