package org.mobyle.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class AuthToken(
    val accessToken: String,
    val tokenType: String = "Bearer",
    val expiresIn: Long, // seconds
    val refreshToken: String? = null,
    val user: User,
    @Transient val isNewUser: Boolean = false
)

data class JWTClaims(
    val userId: String,
    val email: String,
    val username: String,
    val avatar: String?,
    val iat: Long, // issued at (Unix timestamp)
    val exp: Long  // expiration (Unix timestamp)
)
