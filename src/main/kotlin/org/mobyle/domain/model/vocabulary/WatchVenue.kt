package org.mobyle.domain.model.vocabulary

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Where a movie was watched. */
@Serializable
enum class WatchVenue(val wireValue: String) {
    @SerialName("cinema") CINEMA("cinema"),
    @SerialName("streaming") STREAMING("streaming"),
    @SerialName("tv") TV("tv"),
    @SerialName("physical_media") PHYSICAL_MEDIA("physical_media"),
    @SerialName("festival") FESTIVAL("festival"),
    @SerialName("other") OTHER("other");

    companion object {
        fun fromWireValue(value: String): WatchVenue? = entries.firstOrNull { it.wireValue == value }
    }
}
