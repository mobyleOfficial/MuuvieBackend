package org.mobyle.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class LoginAuthTokenResponse(
    val accessToken: String,
    val tokenType: String = "Bearer",
    val expiresIn: Long,
    val refreshToken: String? = null,
    val profile: UserProfile
)
