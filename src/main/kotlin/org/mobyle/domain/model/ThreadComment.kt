package org.mobyle.domain.model

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What a comment hangs from. Maratonas and CineClubes join in B7 and B8. */
@Serializable
enum class CommentTarget(val wireValue: String) {
    @SerialName("watch_log") WATCH_LOG("watch_log"),
    // The rating (user_movies row) of a user for a movie.
    @SerialName("rating") RATING("rating");

    companion object {
        fun fromWireValue(value: String): CommentTarget? = entries.firstOrNull { it.wireValue == value }
    }
}

@Serializable
data class CommentAuthor(val id: String, val username: String, val avatarUrl: String?)

/** A real comment on a watch log or a rating (F2.8). The legacy mocked `Comment` goes in B11. */
@Serializable
data class ThreadComment(
    val id: Long,
    val targetType: CommentTarget,
    val targetId: Long,
    val parentId: Long?,
    val author: CommentAuthor,
    val body: String,
    val hasSpoiler: Boolean,
    val createdAt: Instant,
    val self: Boolean = false
)
