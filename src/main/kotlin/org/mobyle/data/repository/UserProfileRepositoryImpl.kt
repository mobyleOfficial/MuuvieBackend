package org.mobyle.data.repository

import org.mobyle.data.local.profile.UserProfileDataSource
import org.mobyle.domain.model.HighlightDraft
import org.mobyle.domain.model.MovieCard
import org.mobyle.domain.model.ProfileDraft
import org.mobyle.domain.model.ProfileHighlight
import org.mobyle.domain.model.ProfileRecord
import org.mobyle.domain.model.ProfileStats
import org.mobyle.domain.model.vocabulary.Visibility
import org.mobyle.domain.repository.ImportRepository
import org.mobyle.domain.repository.UserProfileRepository

class UserProfileRepositoryImpl(
    private val userProfileDataSource: UserProfileDataSource,
    private val importRepository: ImportRepository
) : UserProfileRepository {
    override fun getProfile(userId: String): ProfileRecord? = userProfileDataSource.getProfile(userId)

    override fun findUserIdByHandle(handle: String): String? = userProfileDataSource.findUserIdByHandle(handle)

    override fun isHandleTaken(handle: String, exceptUserId: String?): Boolean =
        userProfileDataSource.isHandleTaken(handle, exceptUserId)

    override fun updateProfile(userId: String, draft: ProfileDraft) = userProfileDataSource.updateProfile(userId, draft)

    override fun stats(ownerId: String, visibilities: Set<Visibility>): ProfileStats =
        userProfileDataSource.stats(ownerId, visibilities)

    override fun isFollowing(followerId: String, followedId: String): Boolean =
        userProfileDataSource.isFollowing(followerId, followedId)

    override fun getHighlights(ownerId: String): List<ProfileHighlight> = userProfileDataSource.getHighlights(ownerId)

    override fun saveHighlights(ownerId: String, highlights: List<HighlightDraft>) =
        userProfileDataSource.saveHighlights(ownerId, highlights)

    override fun movieCards(movieIds: List<Long>): List<MovieCard> = userProfileDataSource.movieCards(movieIds)

    override fun isImporting(userId: String): Boolean = importRepository.hasRunningJob(userId)
}
