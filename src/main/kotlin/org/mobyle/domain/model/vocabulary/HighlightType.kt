package org.mobyle.domain.model.vocabulary

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Configurable sections of the profile's Destaques tab (F5.4). A profile has at most one of each. */
@Serializable
enum class HighlightType(val wireValue: String) {
    @SerialName("top4") TOP4("top4"),
    @SerialName("shelf") SHELF("shelf"),
    @SerialName("locadora") LOCADORA("locadora"),
    // A Spotify or YouTube Music link.
    @SerialName("soundtrack") SOUNDTRACK("soundtrack");

    companion object {
        fun fromWireValue(value: String): HighlightType? = entries.firstOrNull { it.wireValue == value }
    }
}
