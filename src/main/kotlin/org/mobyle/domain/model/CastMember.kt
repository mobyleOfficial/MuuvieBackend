package org.mobyle.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class CastMember(
    val person: Person,
    val character: String?,
    val order: Int?
)
