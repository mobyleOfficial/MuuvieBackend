package org.mobyle.domain.repository

import org.mobyle.domain.model.FollowEdge
import org.mobyle.domain.model.FollowStatus
import org.mobyle.domain.model.Page
import org.mobyle.domain.model.PageRequest
import org.mobyle.domain.model.UserSummary

/** Follows between users (F6.3). Lists are newest follow first. */
interface FollowRepository {
    fun userSummary(userId: String): UserSummary?
    fun status(viewerId: String, otherId: String): FollowStatus
    /** How [viewerId] relates to each of [userIds]. */
    fun statuses(viewerId: String, userIds: Collection<String>): Map<String, FollowStatus>
    /** False when [followerId] already followed [followedId]. */
    fun follow(followerId: String, followedId: String): Boolean
    /** False when there was no follow to undo. */
    fun unfollow(followerId: String, followedId: String): Boolean
    fun followers(userId: String, page: PageRequest): Page<FollowEdge>
    fun following(userId: String, page: PageRequest): Page<FollowEdge>
}
