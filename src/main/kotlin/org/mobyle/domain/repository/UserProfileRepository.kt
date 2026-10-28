package org.mobyle.domain.repository

import org.mobyle.domain.model.HighlightDraft
import org.mobyle.domain.model.MovieCard
import org.mobyle.domain.model.ProfileDraft
import org.mobyle.domain.model.ProfileHighlight
import org.mobyle.domain.model.ProfileRecord
import org.mobyle.domain.model.ProfileStats
import org.mobyle.domain.model.vocabulary.Visibility

/** The public profile of the v1 API (B5). Users are identified by their external id. */
interface UserProfileRepository {
    fun getProfile(userId: String): ProfileRecord?
    fun findUserIdByHandle(handle: String): String?
    fun isHandleTaken(handle: String, exceptUserId: String?): Boolean
    fun updateProfile(userId: String, draft: ProfileDraft)
    /** Counters limited to [visibilities], without the shelves a locadora hides from the profile. */
    fun stats(ownerId: String, visibilities: Set<Visibility>): ProfileStats
    fun isFollowing(followerId: String, followedId: String): Boolean
    fun getHighlights(ownerId: String): List<ProfileHighlight>
    fun saveHighlights(ownerId: String, highlights: List<HighlightDraft>)
    fun movieCards(movieIds: List<Long>): List<MovieCard>
    fun isImporting(userId: String): Boolean
}
