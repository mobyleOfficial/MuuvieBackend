package org.mobyle.domain.repository

import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import org.mobyle.domain.model.Activity
import org.mobyle.domain.model.ActivityDraft
import org.mobyle.domain.model.ActivityLoves
import org.mobyle.domain.model.ActivityRecord
import org.mobyle.domain.model.Cursor
import org.mobyle.domain.model.FeedCursor
import org.mobyle.domain.model.vocabulary.ActivitySubject
import org.mobyle.domain.model.vocabulary.ActivityType
import org.mobyle.domain.model.vocabulary.FeedScope
import org.mobyle.domain.model.vocabulary.MovieActivityKind
import org.mobyle.domain.model.vocabulary.Visibility

/**
 * The feed (B6). Reads apply VisibilityPolicy for each actor: everything of the viewer,
 * public and mutual content of mutuals, public content of the rest; hidden activities only
 * reach their actor. Users are identified by their external id.
 */
interface ActivityRepository {
    fun insert(draft: ActivityDraft): Long
    /** Rewrites a stored activity; with [touch], it also moves to the top of the feed. */
    fun update(id: Long, type: ActivityType, visibility: Visibility, payload: JsonObject, touch: Boolean)
    fun delete(id: Long)
    fun getRecord(id: Long): ActivityRecord?
    /** The newest activity of [actorId] about the subject with one of [types], created after [since]. */
    fun findRecent(
        actorId: String,
        subjectType: ActivitySubject,
        subjectId: Long,
        types: Set<ActivityType>,
        since: Instant
    ): ActivityRecord?
    fun findBySubject(actorId: String, subjectType: ActivitySubject, subjectId: Long): List<ActivityRecord>

    fun feed(viewerId: String, scope: FeedScope, cursor: FeedCursor?, limit: Int): Cursor<Activity>
    /** Activities of [ownerId] with one of [visibilities]; hidden ones only with [includeHidden]. */
    fun userActivities(
        viewerId: String?,
        ownerId: String,
        visibilities: Set<Visibility>,
        includeHidden: Boolean,
        cursor: FeedCursor?,
        limit: Int
    ): Cursor<Activity>
    /**
     * Activities about a movie (B13.3). [FeedScope.ALL] is everything [viewerId] may see: public
     * ones from anyone, the ones of people they follow by the visibility rules, and their own (the
     * only scope a guest gets). [FeedScope.FOLLOWING] is people they follow, [FeedScope.ME] their own.
     * [kind] null keeps every kind.
     */
    fun movieActivities(
        viewerId: String?,
        movieId: Long,
        scope: FeedScope,
        kind: MovieActivityKind?,
        cursor: FeedCursor?,
        limit: Int
    ): Cursor<Activity>
    /** Public, not hidden activities about a subject, from anyone (B7: the progress of a maratona). */
    fun subjectActivities(viewerId: String?, subjectType: ActivitySubject, subjectId: Long, cursor: FeedCursor?, limit: Int): Cursor<Activity>

    fun setHidden(id: Long, hidden: Boolean)
    fun lovedBy(id: Long, userId: String): Boolean
    fun love(id: Long, userId: String): ActivityLoves
    fun unlove(id: Long, userId: String): ActivityLoves

    /** Ratings are as visible as the user's Já assisti. */
    fun watchedVisibility(userId: String): Visibility
}
