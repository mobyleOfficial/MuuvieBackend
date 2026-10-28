package org.mobyle.domain.repository

import org.mobyle.domain.model.CommentTarget
import org.mobyle.domain.model.Page
import org.mobyle.domain.model.PageRequest
import org.mobyle.domain.model.ThreadComment

/** Real comments (F2.8). Deleted comments are kept with `deleted_at` and never listed. */
interface CommentThreadRepository {
    fun getComments(target: CommentTarget, targetId: Long, page: PageRequest): Page<ThreadComment>
    fun getComment(commentId: Long): ThreadComment?
    fun createComment(userId: String, target: CommentTarget, targetId: Long, parentId: Long?, body: String, hasSpoiler: Boolean): ThreadComment
    fun deleteComment(commentId: Long)
    /** External id of the user who owns the rating (user_movies row), or null when there is no rating. */
    fun ratingOwner(userMovieId: Long): String?
}
