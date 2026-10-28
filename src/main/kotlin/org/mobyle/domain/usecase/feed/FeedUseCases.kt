package org.mobyle.domain.usecase.feed

import org.mobyle.domain.model.Activity
import org.mobyle.domain.model.ActivityLoves
import org.mobyle.domain.model.ActivityRecord
import org.mobyle.domain.model.Cursor
import org.mobyle.domain.model.FeedCursor
import org.mobyle.domain.model.ForbiddenException
import org.mobyle.domain.model.NotFoundException
import org.mobyle.domain.model.SocialEvent
import org.mobyle.domain.model.ValidationException
import org.mobyle.domain.model.vocabulary.FeedScope
import org.mobyle.domain.model.vocabulary.MovieActivityKind
import org.mobyle.domain.policy.ViewerRelation
import org.mobyle.domain.policy.VisibilityPolicy
import org.mobyle.domain.repository.ActivityRepository
import org.mobyle.domain.repository.FollowRepository
import org.mobyle.domain.repository.SocialNotifier

private const val FEED_PAGE_SIZE = 20
private const val FEED_MAX_PAGE_SIZE = 50

private fun feedLimit(limit: Int?): Int {
    val value = limit ?: FEED_PAGE_SIZE
    if (value !in 1..FEED_MAX_PAGE_SIZE) throw ValidationException("limit", "limit must be between 1 and $FEED_MAX_PAGE_SIZE")
    return value
}

/** Who may see an activity: VisibilityPolicy for the actor, and hidden ones only by the actor. */
class ActivityAccess(private val repository: ActivityRepository, private val follows: FollowRepository) {

    fun relation(viewerId: String?, ownerId: String): ViewerRelation = when {
        viewerId == null -> ViewerRelation.OTHER
        viewerId == ownerId -> ViewerRelation.OWNER
        follows.status(viewerId, ownerId).mutual -> ViewerRelation.MUTUAL
        else -> ViewerRelation.OTHER
    }

    /** An activity the viewer can see; any other does not exist for them. */
    fun visibleActivity(viewerId: String?, activityId: Long): ActivityRecord {
        val record = repository.getRecord(activityId) ?: throw NotFoundException("Activity not found")
        val relation = relation(viewerId, record.actorId)
        if (!VisibilityPolicy.canView(relation, record.visibility)) throw NotFoundException("Activity not found")
        if (record.hidden && relation != ViewerRelation.OWNER) throw NotFoundException("Activity not found")
        return record
    }
}

/** The Atts tab (F6.2): me and the people I follow, only them, or only me. */
class GetFeed(private val repository: ActivityRepository) {
    operator fun invoke(viewerId: String, scope: String?, cursor: String?, limit: Int?): Cursor<Activity> {
        val feedScope = scope?.let { FeedScope.fromWireValue(it) ?: throw ValidationException("scope", "Unknown scope") }
            ?: FeedScope.ALL
        return repository.feed(viewerId, feedScope, FeedCursor.decode(cursor), feedLimit(limit))
    }
}

/** Activities of one profile as the viewer may see them. */
class GetProfileActivities(private val repository: ActivityRepository, private val access: ActivityAccess) {
    operator fun invoke(viewerId: String?, ownerId: String, cursor: String?, limit: Int?): Cursor<Activity> {
        val relation = access.relation(viewerId, ownerId)
        return repository.userActivities(
            viewerId, ownerId, VisibilityPolicy.visibleTo(relation), includeHidden = relation == ViewerRelation.OWNER,
            cursor = FeedCursor.decode(cursor), limit = feedLimit(limit)
        )
    }
}

/**
 * The movie's activities (B13.3): [scope] `all` (everything the viewer may see, the only one
 * for guests), `following` (the default) or `me`; [type] `ratings`, `updates` or null for both.
 */
class GetMovieActivities(private val repository: ActivityRepository) {
    operator fun invoke(viewerId: String?, movieId: Long, scope: String?, type: String?, cursor: String?, limit: Int?): Cursor<Activity> {
        val feedScope = scope?.let { FeedScope.fromWireValue(it) ?: throw ValidationException("scope", "Unknown scope") }
            ?: FeedScope.FOLLOWING
        val kind = type?.let { MovieActivityKind.fromWireValue(it) ?: throw ValidationException("type", "Unknown type") }
        return repository.movieActivities(viewerId, movieId, feedScope, kind, FeedCursor.decode(cursor), feedLimit(limit))
    }
}

/** "Amei" on any activity the viewer can see; the actor is told, unless it is their own. */
class LoveActivity(
    private val repository: ActivityRepository,
    private val access: ActivityAccess,
    private val follows: FollowRepository,
    private val notifier: SocialNotifier
) {
    operator fun invoke(userId: String, activityId: Long): ActivityLoves {
        val record = access.visibleActivity(userId, activityId)
        val alreadyLoved = repository.lovedBy(activityId, userId)
        val loves = repository.love(activityId, userId)
        if (!alreadyLoved && record.actorId != userId) {
            follows.userSummary(userId)?.let { notifier.notify(record.actorId, SocialEvent.ActivityLoved(activityId, it)) }
        }
        return loves
    }
}

class UnloveActivity(private val repository: ActivityRepository, private val access: ActivityAccess) {
    operator fun invoke(userId: String, activityId: Long): ActivityLoves {
        access.visibleActivity(userId, activityId)
        return repository.unlove(activityId, userId)
    }
}

/** Hides one of my activities from everyone else's feed, or shows it again (F6.2). */
class HideActivity(private val repository: ActivityRepository, private val access: ActivityAccess) {
    operator fun invoke(userId: String, activityId: Long, hidden: Boolean) {
        val record = access.visibleActivity(userId, activityId)
        if (record.actorId != userId) throw ForbiddenException("not_activity_owner", "Only the author can hide this activity")
        repository.setHidden(activityId, hidden)
    }
}
