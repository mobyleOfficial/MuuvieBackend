package org.mobyle.model

import kotlinx.serialization.Serializable

@Serializable
data class CreateLocadoraRequest(
    val name: String,
    val description: String? = null,
    val visibility: String = "public"
)
