package org.mobyle.model

import kotlinx.serialization.Serializable

@Serializable
data class AddShelfToLocadoraRequest(
    val shelfId: Long
)
