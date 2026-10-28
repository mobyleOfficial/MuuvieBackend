package org.mobyle.data.repository

import org.mobyle.data.local.comments.CommentThreadDataSource
import org.mobyle.domain.model.CommentTarget
import org.mobyle.domain.model.Page
import org.mobyle.domain.model.PageRequest
import org.mobyle.domain.model.ThreadComment
import org.mobyle.domain.repository.CommentThreadRepository

class CommentThreadRepositoryImpl(private val commentThreadDataSource: CommentThreadDataSource) : CommentThreadRepository {
    override fun getComments(target: CommentTarget, targetId: Long, page: PageRequest): Page<ThreadComment> =
        commentThreadDataSource.getComments(target, targetId, page)

    override fun getComment(commentId: Long): ThreadComment? = commentThreadDataSource.getComment(commentId)

    override fun createComment(
        userId: String,
        target: CommentTarget,
        targetId: Long,
        parentId: Long?,
        body: String,
        hasSpoiler: Boolean
    ): ThreadComment = commentThreadDataSource.createComment(userId, target, targetId, parentId, body, hasSpoiler)

    override fun deleteComment(commentId: Long) = commentThreadDataSource.deleteComment(commentId)

    override fun ratingOwner(userMovieId: Long): String? = commentThreadDataSource.ratingOwner(userMovieId)
}
