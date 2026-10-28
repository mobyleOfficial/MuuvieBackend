package org.mobyle.domain.model.vocabulary

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** How the progress of a watch session is expressed. */
@Serializable
enum class WatchMode(val wireValue: String) {
    @SerialName("watched") WATCHED("watched"),
    @SerialName("minutes") MINUTES("minutes"),
    @SerialName("percent") PERCENT("percent");

    companion object {
        fun fromWireValue(value: String): WatchMode? = entries.firstOrNull { it.wireValue == value }
    }
}
