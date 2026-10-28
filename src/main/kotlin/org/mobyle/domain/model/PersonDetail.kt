package org.mobyle.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class PersonCredit(
    val movie: Movie,
    val role: String,
    val character: String?
)

@Serializable
data class PersonDetail(
    val person: Person,
    val credits: List<PersonCredit>
)
