package org.mobyle.domain.model.vocabulary

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Shelves every account has. They cannot be renamed or deleted. */
@Serializable
enum class SystemShelfType(val wireValue: String) {
    // "Já assisti"
    @SerialName("watched") WATCHED("watched"),
    // "Quero ver"
    @SerialName("watchlist") WATCHLIST("watchlist");

    companion object {
        fun fromWireValue(value: String): SystemShelfType? = entries.firstOrNull { it.wireValue == value }
    }
}
