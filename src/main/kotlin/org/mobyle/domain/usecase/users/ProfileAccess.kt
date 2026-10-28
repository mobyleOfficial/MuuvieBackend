package org.mobyle.domain.usecase.users

import org.mobyle.domain.model.NotFoundException
import org.mobyle.domain.model.Profile
import org.mobyle.domain.policy.ViewerRelation
import org.mobyle.domain.policy.VisibilityPolicy
import org.mobyle.domain.repository.UserProfileRepository
import org.mobyle.domain.usecase.shelves.ShelfAccess

/** Finds a profile by id or @handle and puts it together as the viewer sees it (F5.1, F5.5). */
class ProfileAccess(private val repository: UserProfileRepository, private val shelves: ShelfAccess) {

    /** The external id behind [idOrHandle]: a user id, or a handle starting with @. */
    fun resolveUserId(idOrHandle: String): String {
        val userId = if (idOrHandle.startsWith("@")) {
            repository.findUserIdByHandle(normalizeHandle(idOrHandle))
        } else {
            idOrHandle.takeIf { repository.getProfile(it) != null }
        }
        return userId ?: throw NotFoundException("User not found")
    }

    fun profile(viewerId: String?, ownerId: String): Profile {
        val record = repository.getProfile(ownerId) ?: throw NotFoundException("User not found")
        val relation = shelves.relation(viewerId, ownerId)
        val owner = relation == ViewerRelation.OWNER
        val isFollowing = viewerId != null && !owner && repository.isFollowing(viewerId, ownerId)
        val followsMe = viewerId != null && !owner && repository.isFollowing(ownerId, viewerId)
        return Profile(
            id = record.id,
            username = record.username,
            handle = record.handle,
            displayName = record.displayName,
            pronouns = record.pronouns,
            bio = record.bio,
            avatarUrl = record.avatarUrl,
            bannerUrl = record.bannerUrl,
            accentColor = record.accentColor,
            links = record.links,
            featured = record.featured,
            stats = repository.stats(ownerId, VisibilityPolicy.visibleTo(relation)),
            isFollowing = isFollowing,
            followsMe = followsMe,
            mutual = isFollowing && followsMe,
            self = owner
        )
    }
}
