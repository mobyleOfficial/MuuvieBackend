package org.mobyle.domain.model.vocabulary

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Who can see a user-owned resource (shelf, locadora, watch log, activity). */
@Serializable
enum class Visibility(val wireValue: String) {
    @SerialName("public") PUBLIC("public"),
    // Only users who follow each other.
    @SerialName("mutual") MUTUAL("mutual"),
    @SerialName("secret") SECRET("secret");

    companion object {
        val DEFAULT = PUBLIC

        fun fromWireValue(value: String): Visibility? = entries.firstOrNull { it.wireValue == value }

        /** Values the app sent before the shared vocabulary ("private", "mutuals"); anything unknown is public. */
        fun fromLegacyValue(value: String): Visibility = when (value) {
            "private" -> SECRET
            "mutuals" -> MUTUAL
            else -> fromWireValue(value) ?: DEFAULT
        }
    }
}
