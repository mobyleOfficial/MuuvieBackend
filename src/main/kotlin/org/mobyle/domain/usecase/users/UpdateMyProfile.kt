package org.mobyle.domain.usecase.users

import org.mobyle.domain.model.ConflictException
import org.mobyle.domain.model.Profile
import org.mobyle.domain.repository.UserProfileRepository

/** Edits the own profile (F5.3). A handle in use by someone else is a 409. */
class UpdateMyProfile(private val repository: UserProfileRepository, private val getMyProfile: GetMyProfile) {
    operator fun invoke(
        userId: String,
        displayName: String?,
        handle: String?,
        pronouns: String?,
        bio: String?,
        links: List<String>,
        accentColor: String?,
        avatarUrl: String?,
        bannerUrl: String?
    ): Profile {
        val draft = profileDraftOf(displayName, handle, pronouns, bio, links, accentColor, avatarUrl, bannerUrl)
        if (draft.handle != null && repository.isHandleTaken(draft.handle, exceptUserId = userId)) {
            throw ConflictException("handle_taken", "Handle is already in use")
        }
        repository.updateProfile(userId, draft)
        return getMyProfile(userId)
    }
}
