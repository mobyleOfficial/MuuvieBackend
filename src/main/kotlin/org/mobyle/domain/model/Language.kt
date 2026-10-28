package org.mobyle.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class Language(
    val iso: String,
    val englishName: String,
    // The endonym ("Français"), the usual way to list languages (B14.3).
    val name: String = englishName
)
