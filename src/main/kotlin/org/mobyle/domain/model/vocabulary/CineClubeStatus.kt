package org.mobyle.domain.model.vocabulary

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Where a CineClube is in its sessions (B8). The scheduler moves it from upcoming to finished. */
@Serializable
enum class CineClubeStatus(val wireValue: String) {
    // Created and open to join, before the first session.
    @SerialName("upcoming") UPCOMING("upcoming"),
    @SerialName("active") ACTIVE("active"),
    @SerialName("finished") FINISHED("finished"),
    // Cancelled by the owner: read only.
    @SerialName("cancelled") CANCELLED("cancelled");

    /** People can join (or leave) before the start and while it runs. */
    val open: Boolean get() = this == UPCOMING || this == ACTIVE

    companion object {
        fun fromWireValue(value: String): CineClubeStatus? = entries.firstOrNull { it.wireValue == value }
    }
}

/**
 * Where a participant is with the movie of a session, from their watch logs within its period:
 * nothing logged yet, a partial session, or a completed one.
 */
@Serializable
enum class SessionProgress(val wireValue: String) {
    @SerialName("pending") PENDING("pending"),
    @SerialName("watching") WATCHING("watching"),
    @SerialName("completed") COMPLETED("completed");

    companion object {
        fun fromWireValue(value: String): SessionProgress? = entries.firstOrNull { it.wireValue == value }
    }
}
