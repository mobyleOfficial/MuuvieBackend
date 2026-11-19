package org.mobyle.domain.model

import kotlinx.serialization.Serializable

/** The identity providers an account signs in with. There is no password login. */
enum class SocialProvider(val wire: String) {
    APPLE("apple"),
    GOOGLE("google");

    companion object {
        fun fromWire(value: String): SocialProvider? = entries.firstOrNull { it.wire == value.trim().lowercase() }
    }
}

/** Who the provider says signed in, read from an ID token whose signature and claims were checked. */
data class SocialIdentity(
    val provider: SocialProvider,
    /** The provider's stable user id (`sub`). */
    val subject: String,
    val email: String?,
    val emailVerified: Boolean,
    val picture: String? = null
)

/**
 * Body of `POST /auth/social`. [idToken] is the ID token the native SDK returned. [nonce] is the raw
 * nonce the app generated; the app hands the provider its SHA-256 (hex), which the token carries back.
 */
@Serializable
data class SocialSignInRequest(
    val provider: String,
    val idToken: String,
    val nonce: String? = null
)

@Serializable
data class NicknameRequest(val nickname: String)

@Serializable
data class NicknameResponse(val nickname: String)
