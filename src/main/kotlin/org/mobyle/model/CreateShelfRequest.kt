package org.mobyle.model

import kotlinx.serialization.Serializable

@Serializable
data class CreateShelfRequest(
    val name: String,
    val description: String? = null,
    val color: String? = null,
    val visibility: String = "public"
)
