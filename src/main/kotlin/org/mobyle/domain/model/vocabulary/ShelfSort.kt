package org.mobyle.domain.model.vocabulary

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Ordering of the movies inside a shelf. MANUAL keeps the saved positions. */
@Serializable
enum class ShelfSort(val wireValue: String) {
    @SerialName("manual") MANUAL("manual"),
    @SerialName("title") TITLE("title"),
    @SerialName("director") DIRECTOR("director"),
    @SerialName("release_year") RELEASE_YEAR("release_year"),
    @SerialName("added_newest") ADDED_NEWEST("added_newest"),
    @SerialName("added_oldest") ADDED_OLDEST("added_oldest"),
    @SerialName("updated_newest") UPDATED_NEWEST("updated_newest"),
    @SerialName("watched_first") WATCHED_FIRST("watched_first"),
    @SerialName("watched_last") WATCHED_LAST("watched_last"),
    @SerialName("progress_highest") PROGRESS_HIGHEST("progress_highest"),
    @SerialName("rating_highest") RATING_HIGHEST("rating_highest");

    companion object {
        val DEFAULT = MANUAL

        fun fromWireValue(value: String): ShelfSort? = entries.firstOrNull { it.wireValue == value }
    }
}
