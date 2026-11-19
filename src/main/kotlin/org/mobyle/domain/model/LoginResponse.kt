package org.mobyle.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class LoginAuthTokenResponse(
    val accessToken: String,
    val tokenType: String = "Bearer",
    val expiresIn: Long,
    val refreshToken: String? = null,
    val profile: UserProfile,
    // True until the account picks its nickname (PUT /auth/nickname); a new account starts with a generated one.
    val needsNickname: Boolean = false
)
