package org.mobyle.domain.model.vocabulary

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Where an import reads from. Letterboxd is reserved until its input format is decided (A1). */
@Serializable
enum class ImportSource(val wireValue: String) {
    @SerialName("filmow") FILMOW("filmow"),
    @SerialName("letterboxd") LETTERBOXD("letterboxd");

    companion object {
        fun fromWireValue(value: String): ImportSource? = entries.firstOrNull { it.wireValue == value }
    }
}
