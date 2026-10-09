package org.mobyle.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class Person(
    val id: Long,
    val tmdbPersonId: Int?,
    val name: String,
    val profilePath: String?
)
