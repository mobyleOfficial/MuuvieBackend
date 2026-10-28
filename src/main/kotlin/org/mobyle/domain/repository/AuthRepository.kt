package org.mobyle.domain.repository

import org.mobyle.domain.model.AuthToken
import org.mobyle.domain.model.JWTClaims
import org.mobyle.domain.model.OAuthCallbackRequest
import org.mobyle.domain.model.User

interface AuthRepository {
    suspend fun processOAuthCallback(request: OAuthCallbackRequest): Result<AuthToken>
    suspend fun validateToken(token: String): Result<JWTClaims>
    suspend fun refreshToken(refreshToken: String): Result<AuthToken>
    suspend fun getUserById(userId: String): Result<User>
    suspend fun loginUser(email: String, password: String): Result<AuthToken>
    suspend fun signUpUser(email: String, password: String, nickname: String): Result<AuthToken>
    suspend fun logoutUser(token: String, refreshToken: String? = null): Result<Unit>
    suspend fun checkNicknameAvailability(nickname: String): Result<Boolean>
}

interface UserRepository {
    suspend fun createOrUpdateUser(user: User): Result<User>
    suspend fun getUserByEmail(email: String): Result<User?>
    suspend fun getUserById(userId: String): Result<User?>
}
