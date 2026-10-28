package org.mobyle.domain.model.vocabulary

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** pending -> reading -> preview -> importing -> done; failed or cancelled end it early. */
@Serializable
enum class ImportStatus(val wireValue: String) {
    @SerialName("pending") PENDING("pending"),
    // Reading the source (e.g. scraping Filmow).
    @SerialName("reading") READING("reading"),
    // Read; waiting for the user to confirm the preview. Nothing was written yet.
    @SerialName("preview") PREVIEW("preview"),
    @SerialName("importing") IMPORTING("importing"),
    @SerialName("done") DONE("done"),
    @SerialName("failed") FAILED("failed"),
    @SerialName("cancelled") CANCELLED("cancelled");

    val isActive: Boolean get() = this in ACTIVE

    /** Cancelling is only allowed before anything is written. */
    val isCancellable: Boolean get() = this == PENDING || this == READING || this == PREVIEW

    companion object {
        val ACTIVE = setOf(PENDING, READING, PREVIEW, IMPORTING)
        val RUNNING = setOf(PENDING, READING, IMPORTING)

        fun fromWireValue(value: String): ImportStatus? = entries.firstOrNull { it.wireValue == value }
    }
}
