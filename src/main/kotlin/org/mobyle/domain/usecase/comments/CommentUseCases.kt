package org.mobyle.domain.usecase.comments

import org.mobyle.domain.model.CommentTarget
import org.mobyle.domain.model.ForbiddenException
import org.mobyle.domain.model.NotFoundException
import org.mobyle.domain.model.Page
import org.mobyle.domain.model.PageRequest
import org.mobyle.domain.model.ThreadComment
import org.mobyle.domain.model.ValidationException
import org.mobyle.domain.repository.CommentThreadRepository
import org.mobyle.domain.usecase.watch.WatchLogAccess

private const val BODY_MAX_LENGTH = 2000

/** Who may see a comment target. A hidden target, and so its comments, answers 404. */
class CommentAccess(private val repository: CommentThreadRepository, private val logAccess: WatchLogAccess) {

    fun target(value: String): CommentTarget =
        CommentTarget.fromWireValue(value) ?: throw ValidationException("targetType", "targetType must be watch_log or rating")

    /** External id of the target's owner, if [viewerId] can see the target. */
    fun visibleTargetOwner(viewerId: String?, target: CommentTarget, targetId: Long): String = when (target) {
        CommentTarget.WATCH_LOG -> logAccess.visibleLog(viewerId, targetId).userId
        CommentTarget.RATING -> repository.ratingOwner(targetId) ?: throw NotFoundException("Rating not found")
    }
}

/** Comments of a watch log or a rating, oldest first (F2.8). */
class GetThreadComments(private val repository: CommentThreadRepository, private val access: CommentAccess) {
    operator fun invoke(viewerId: String?, targetType: String, targetId: Long, page: PageRequest): Page<ThreadComment> {
        val target = access.target(targetType)
        access.visibleTargetOwner(viewerId, target, targetId)
        val comments = repository.getComments(target, targetId, page)
        return comments.copy(results = comments.results.map { it.copy(self = it.author.id == viewerId) })
    }
}

class PostComment(private val repository: CommentThreadRepository, private val access: CommentAccess) {
    operator fun invoke(
        userId: String,
        targetType: String,
        targetId: Long,
        parentId: Long?,
        body: String,
        hasSpoiler: Boolean
    ): ThreadComment {
        val target = access.target(targetType)
        val trimmed = body.trim()
        if (trimmed.isEmpty() || trimmed.length > BODY_MAX_LENGTH) {
            throw ValidationException("body", "body must have between 1 and $BODY_MAX_LENGTH characters")
        }
        access.visibleTargetOwner(userId, target, targetId)
        if (parentId != null) {
            val parent = repository.getComment(parentId)
            if (parent == null || parent.targetType != target || parent.targetId != targetId) {
                throw ValidationException("parentId", "The parent comment is not on this target")
            }
        }
        return repository.createComment(userId, target, targetId, parentId, trimmed, hasSpoiler).copy(self = true)
    }
}

/** The author, or the owner of the target, can delete a comment. */
class DeleteComment(private val repository: CommentThreadRepository, private val access: CommentAccess) {
    operator fun invoke(userId: String, commentId: Long) {
        val comment = repository.getComment(commentId) ?: throw NotFoundException("Comment not found")
        val targetOwner = access.visibleTargetOwner(userId, comment.targetType, comment.targetId)
        if (userId != comment.author.id && userId != targetOwner) {
            throw ForbiddenException("not_comment_owner", "Only the author or the target owner can delete this comment")
        }
        repository.deleteComment(commentId)
    }
}
