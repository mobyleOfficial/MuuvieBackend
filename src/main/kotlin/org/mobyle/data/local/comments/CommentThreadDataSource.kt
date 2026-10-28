package org.mobyle.data.local.comments

import kotlinx.datetime.Clock
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.mobyle.data.local.database.CommentsTable
import org.mobyle.data.local.database.UserMoviesTable
import org.mobyle.data.local.database.UsersTable
import org.mobyle.data.local.database.userDbIdOf
import org.mobyle.domain.model.CommentAuthor
import org.mobyle.domain.model.CommentTarget
import org.mobyle.domain.model.Page
import org.mobyle.domain.model.PageRequest
import org.mobyle.domain.model.ThreadComment

/** Real comments (F2.8). Users are identified by their external id. */
interface CommentThreadDataSource {
    fun getComments(target: CommentTarget, targetId: Long, page: PageRequest): Page<ThreadComment>
    fun getComment(commentId: Long): ThreadComment?
    fun createComment(userId: String, target: CommentTarget, targetId: Long, parentId: Long?, body: String, hasSpoiler: Boolean): ThreadComment
    fun deleteComment(commentId: Long)
    fun ratingOwner(userMovieId: Long): String?
}

class CommentThreadDataSourceImpl : CommentThreadDataSource {

    override fun getComments(target: CommentTarget, targetId: Long, page: PageRequest): Page<ThreadComment> = transaction {
        val query = (CommentsTable innerJoin UsersTable).selectAll()
            .where {
                (CommentsTable.targetType eq target.wireValue) and (CommentsTable.targetId eq targetId) and
                    CommentsTable.deletedAt.isNull()
            }
        val total = query.count().toInt()
        val comments = query
            .orderBy(CommentsTable.createdAt to SortOrder.ASC, CommentsTable.id to SortOrder.ASC)
            .limit(page.limit, offset = page.offset)
            .map { toComment(it) }
        Page.of(comments, page, total)
    }

    override fun getComment(commentId: Long): ThreadComment? = transaction {
        (CommentsTable innerJoin UsersTable).selectAll()
            .where { (CommentsTable.id eq commentId) and CommentsTable.deletedAt.isNull() }
            .firstOrNull()
            ?.let { toComment(it) }
    }

    override fun createComment(
        userId: String,
        target: CommentTarget,
        targetId: Long,
        parentId: Long?,
        body: String,
        hasSpoiler: Boolean
    ): ThreadComment = transaction {
        val userDbId = userDbIdOf(userId) ?: throw IllegalStateException("User not found")
        val commentId = CommentsTable.insertAndGetId {
            it[CommentsTable.userId] = userDbId
            it[targetType] = target.wireValue
            it[CommentsTable.targetId] = targetId
            it[CommentsTable.parentId] = parentId
            it[CommentsTable.body] = body
            it[CommentsTable.hasSpoiler] = hasSpoiler
            it[createdAt] = Clock.System.now()
        }.value
        getComment(commentId)!!
    }

    override fun deleteComment(commentId: Long) {
        transaction {
            CommentsTable.update({ CommentsTable.id eq commentId }) { it[deletedAt] = Clock.System.now() }
        }
    }

    override fun ratingOwner(userMovieId: Long): String? = transaction {
        (UserMoviesTable innerJoin UsersTable).select(UsersTable.externalId)
            .where { (UserMoviesTable.id eq userMovieId) and UserMoviesTable.rating.isNotNull() }
            .firstOrNull()
            ?.get(UsersTable.externalId)
    }

    private fun toComment(row: ResultRow) = ThreadComment(
        id = row[CommentsTable.id].value,
        targetType = CommentTarget.fromWireValue(row[CommentsTable.targetType])!!,
        targetId = row[CommentsTable.targetId],
        parentId = row[CommentsTable.parentId]?.value,
        author = CommentAuthor(
            id = row[UsersTable.externalId],
            username = row[UsersTable.username],
            avatarUrl = row[UsersTable.avatarUrl]
        ),
        body = row[CommentsTable.body],
        hasSpoiler = row[CommentsTable.hasSpoiler],
        createdAt = row[CommentsTable.createdAt]
    )
}
