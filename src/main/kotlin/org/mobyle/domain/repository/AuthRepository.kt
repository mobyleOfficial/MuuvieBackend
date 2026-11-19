package org.mobyle.domain.repository

import org.mobyle.domain.model.AuthToken
import org.mobyle.domain.model.JWTClaims
import org.mobyle.domain.model.SocialProvider
import org.mobyle.domain.model.User

interface AuthRepository {
    /**
     * Signs in with an Apple or Google ID token: the account linked to that provider account, else the
     * account with the same verified e-mail, else a new account with a generated nickname.
     */
    suspend fun signInWithProvider(provider: SocialProvider, idToken: String, nonce: String?): AuthToken

    /** Sets the nickname of [userId] and returns it trimmed. */
    suspend fun chooseNickname(userId: String, nickname: String): String
    suspend fun validateToken(token: String): Result<JWTClaims>
    suspend fun refreshToken(refreshToken: String): Result<AuthToken>
    suspend fun getUserById(userId: String): Result<User>
    suspend fun logoutUser(token: String, refreshToken: String? = null): Result<Unit>
    suspend fun checkNicknameAvailability(nickname: String): Result<Boolean>
}

interface UserRepository {
    suspend fun createOrUpdateUser(user: User): Result<User>
    suspend fun getUserByEmail(email: String): Result<User?>
    suspend fun getUserById(userId: String): Result<User?>
}
