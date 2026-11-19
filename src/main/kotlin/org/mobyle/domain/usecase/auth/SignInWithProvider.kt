package org.mobyle.domain.usecase.auth

import org.mobyle.domain.model.AuthToken
import org.mobyle.domain.model.SocialProvider
import org.mobyle.domain.model.ValidationException
import org.mobyle.domain.repository.AuthRepository

/** Signs in with the ID token of an Apple or Google native sign-in. */
class SignInWithProvider(private val authRepository: AuthRepository) {
    suspend operator fun invoke(provider: String, idToken: String, nonce: String?): AuthToken {
        val socialProvider = SocialProvider.fromWire(provider)
            ?: throw ValidationException("provider", "provider must be apple or google")
        if (idToken.isBlank()) throw ValidationException("idToken", "idToken is required")
        return authRepository.signInWithProvider(socialProvider, idToken, nonce?.takeIf { it.isNotBlank() })
    }
}
