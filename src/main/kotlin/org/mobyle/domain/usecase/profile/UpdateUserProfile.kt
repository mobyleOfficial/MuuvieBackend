package org.mobyle.domain.usecase.profile

import kotlinx.coroutines.runBlocking
import org.mobyle.domain.model.UserProfile
import org.mobyle.domain.repository.ProfileRepository

class UpdateUserProfile(private val repository: ProfileRepository) {
    operator fun invoke(profile: UserProfile): UpdateProfileResult = runBlocking {
        val username = profile.username.trim()
        when {
            username.isEmpty() || username.length > MAX_USERNAME_LENGTH ->
                UpdateProfileResult.Invalid("username")
            profile.bio.length > MAX_BIO_LENGTH -> UpdateProfileResult.Invalid("bio")
            repository.isUsernameTaken(username, exceptUserId = profile.id) -> UpdateProfileResult.UsernameTaken
            else -> {
                repository.updateUserProfile(profile.copy(username = username))
                UpdateProfileResult.Updated
            }
        }
    }

    companion object {
        // Same limits as the nickname check and the users table.
        const val MAX_USERNAME_LENGTH = 30
        const val MAX_BIO_LENGTH = 500
    }
}

sealed interface UpdateProfileResult {
    data object Updated : UpdateProfileResult
    data object UsernameTaken : UpdateProfileResult
    data class Invalid(val field: String) : UpdateProfileResult
}
