package org.mobyle.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class Country(
    val iso: String,
    val englishName: String,
    // Localized by TMDB in the catalog language (B14.3).
    val name: String = englishName
)
