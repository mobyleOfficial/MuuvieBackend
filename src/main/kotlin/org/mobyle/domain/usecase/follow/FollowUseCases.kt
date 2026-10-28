package org.mobyle.domain.usecase.follow

import org.mobyle.domain.model.FollowCard
import org.mobyle.domain.model.FollowEdge
import org.mobyle.domain.model.FollowStatus
import org.mobyle.domain.model.NotFoundException
import org.mobyle.domain.model.Page
import org.mobyle.domain.model.PageRequest
import org.mobyle.domain.model.SocialEvent
import org.mobyle.domain.model.SystemMessage
import org.mobyle.domain.model.TooManyRequestsException
import org.mobyle.domain.model.ValidationException
import org.mobyle.domain.repository.ConversationRepository
import org.mobyle.domain.repository.FollowRepository
import org.mobyle.domain.repository.RateLimiter
import org.mobyle.domain.repository.SocialNotifier

/** B6 rule: at most 10 follows per minute per user. */
private const val FOLLOWS_PER_MINUTE = 10
private const val MINUTE_MS = 60_000L

/**
 * Tells both members of an existing conversation when they stop or start following each other
 * again: the conversation turns read-only or writable (F6.5).
 */
class MutualChangeNotice(private val conversations: ConversationRepository, private val notifier: SocialNotifier) {
    fun announce(userId: String, otherId: String, mutualNow: Boolean) {
        val conversationId = conversations.findConversation(userId, otherId) ?: return
        val code = if (mutualNow) SystemMessage.MUTUAL_RESTORED else SystemMessage.MUTUAL_LOST
        val message = conversations.addMessage(conversationId, null, code)
        listOf(userId, otherId).forEach { notifier.notify(it, SocialEvent.MessageReceived(message, null)) }
    }
}

/** Follows a user (F6.3). Idempotent; the followed user is told about a new follower. */
class FollowAccount(
    private val follows: FollowRepository,
    private val notifier: SocialNotifier,
    private val rateLimiter: RateLimiter,
    private val mutualChange: MutualChangeNotice
) {
    operator fun invoke(userId: String, targetId: String): FollowStatus {
        if (userId == targetId) throw ValidationException("userId", "Cannot follow yourself")
        follows.userSummary(targetId) ?: throw NotFoundException("User not found")
        val before = follows.status(userId, targetId)
        if (before.following) return before
        if (!rateLimiter.tryAcquire("follow:$userId", FOLLOWS_PER_MINUTE, MINUTE_MS)) {
            throw TooManyRequestsException("rate_limited", "Too many follows, try again in a minute")
        }
        if (follows.follow(userId, targetId)) {
            follows.userSummary(userId)?.let { notifier.notify(targetId, SocialEvent.NewFollower(it)) }
            if (before.followedBy) mutualChange.announce(userId, targetId, mutualNow = true)
        }
        return follows.status(userId, targetId)
    }
}

/** Unfollows a user. Idempotent; a conversation between mutuals becomes read-only. */
class UnfollowAccount(private val follows: FollowRepository, private val mutualChange: MutualChangeNotice) {
    operator fun invoke(userId: String, targetId: String): FollowStatus {
        val before = follows.status(userId, targetId)
        if (follows.unfollow(userId, targetId) && before.mutual) {
            mutualChange.announce(userId, targetId, mutualNow = false)
        }
        return follows.status(userId, targetId)
    }
}

class GetFollowStatus(private val follows: FollowRepository) {
    operator fun invoke(viewerId: String, userId: String): FollowStatus {
        follows.userSummary(userId) ?: throw NotFoundException("User not found")
        return follows.status(viewerId, userId)
    }
}

/** Followers or following of a user, each with how the viewer relates to them (F6.3). */
class GetFollowList(private val follows: FollowRepository) {
    fun followers(viewerId: String, userId: String, page: PageRequest): Page<FollowCard> {
        follows.userSummary(userId) ?: throw NotFoundException("User not found")
        return cards(viewerId, follows.followers(userId, page))
    }

    fun following(viewerId: String, userId: String, page: PageRequest): Page<FollowCard> {
        follows.userSummary(userId) ?: throw NotFoundException("User not found")
        return cards(viewerId, follows.following(userId, page))
    }

    private fun cards(viewerId: String, edges: Page<FollowEdge>): Page<FollowCard> {
        val statuses = follows.statuses(viewerId, edges.results.map { it.user.id })
        val results = edges.results.map { edge ->
            val status = statuses[edge.user.id] ?: FollowStatus.of(following = false, followedBy = false)
            FollowCard(
                user = edge.user,
                isFollowing = status.following,
                followsMe = status.followedBy,
                mutual = status.mutual,
                self = edge.user.id == viewerId,
                followedAt = edge.followedAt
            )
        }
        return Page(results, edges.page, edges.totalPages, edges.totalResults, edges.hasNext)
    }
}
