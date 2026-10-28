package org.mobyle.domain.model.vocabulary

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Situation of a movie for a user, mirrored in `user_movies.status` from the system shelves
 * (domain ADR, rule 3). "Na geladeira" and "Abandonei" are ratings since phase 13.
 */
@Serializable
enum class WatchStatus(val wireValue: String) {
    @SerialName("watched") WATCHED("watched"),
    @SerialName("want_to_watch") WANT_TO_WATCH("want_to_watch");

    companion object {
        fun fromWireValue(value: String): WatchStatus? = entries.firstOrNull { it.wireValue == value }
    }
}
