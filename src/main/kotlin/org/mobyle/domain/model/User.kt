package org.mobyle.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class User(
    val id: String,
    val email: String,
    val username: String,
    val avatar: String? = null,
    val bio: String? = null,
    val createdAt: String,
    @Transient val passwordHash: String? = null,
    // False while an account created by social sign-in still carries its generated nickname.
    @Transient val nicknameConfirmed: Boolean = true
)
