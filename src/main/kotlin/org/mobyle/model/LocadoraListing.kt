package org.mobyle.model

import kotlinx.serialization.Serializable
import org.mobyle.domain.model.Locadora

@Serializable
data class LocadoraListing(
    val totalPages: Int,
    val totalResults: Int,
    val locadoras: List<Locadora>
)
