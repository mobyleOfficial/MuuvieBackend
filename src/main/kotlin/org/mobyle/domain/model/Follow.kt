package org.mobyle.domain.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/** How the viewer and another user follow each other (F6.3). */
@Serializable
data class FollowStatus(val following: Boolean, val followedBy: Boolean, val mutual: Boolean) {
    companion object {
        fun of(following: Boolean, followedBy: Boolean) = FollowStatus(following, followedBy, following && followedBy)
    }
}

/**
 * A user of the followers and following lists (v1). The flags are about the viewer, so the
 * app can show "Seguir", "Seguir de volta" or "Seguindo" on each row. [followedAt] is when
 * the follow of the list was made.
 */
@Serializable
data class FollowCard(
    val user: UserSummary,
    val isFollowing: Boolean,
    val followsMe: Boolean,
    val mutual: Boolean,
    val self: Boolean,
    val followedAt: Instant
)

/** A follow row with the users it links, before the viewer flags are added. */
data class FollowEdge(val user: UserSummary, val followedAt: Instant)
