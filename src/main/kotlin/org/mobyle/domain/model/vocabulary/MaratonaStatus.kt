package org.mobyle.domain.model.vocabulary

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Where a maratona is in its period (B7). The scheduler moves it from upcoming to finished. */
@Serializable
enum class MaratonaStatus(val wireValue: String) {
    // Created and open to join, before starts_on.
    @SerialName("upcoming") UPCOMING("upcoming"),
    @SerialName("active") ACTIVE("active"),
    @SerialName("finished") FINISHED("finished"),
    // Cancelled by the owner: read only.
    @SerialName("cancelled") CANCELLED("cancelled");

    /** People can join (or leave) before the start and while it runs. */
    val open: Boolean get() = this == UPCOMING || this == ACTIVE

    companion object {
        fun fromWireValue(value: String): MaratonaStatus? = entries.firstOrNull { it.wireValue == value }
    }
}

/** How a challenge gets done: by its criteria against the movie metadata, or only by hand. */
@Serializable
enum class ChallengeType(val wireValue: String) {
    @SerialName("criteria") CRITERIA("criteria"),
    @SerialName("free") FREE("free");

    companion object {
        fun fromWireValue(value: String): ChallengeType? = entries.firstOrNull { it.wireValue == value }
    }
}

/** Who tied a movie to a challenge: the participant, or the criteria when a session was logged. */
@Serializable
enum class ChallengeLinkSource(val wireValue: String) {
    @SerialName("manual") MANUAL("manual"),
    @SerialName("auto") AUTO("auto");

    companion object {
        fun fromWireValue(value: String): ChallengeLinkSource? = entries.firstOrNull { it.wireValue == value }
    }
}
