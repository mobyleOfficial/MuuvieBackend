package org.mobyle.domain.usecase.auth

import org.mobyle.domain.repository.AuthRepository

class LogoutUser(private val authRepository: AuthRepository) {
    suspend operator fun invoke(token: String, refreshToken: String? = null): Result<Unit> {
        return authRepository.logoutUser(token, refreshToken)
    }
}
