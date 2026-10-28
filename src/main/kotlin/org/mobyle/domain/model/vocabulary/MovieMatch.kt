package org.mobyle.domain.model.vocabulary

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Whether a catalog movie is linked to TMDB. Movies scraped from a source start as [PENDING];
 * [UNMATCHED] means no TMDB movie matched with confidence and the queue stops retrying it.
 */
@Serializable
enum class MovieMatchStatus(val wireValue: String) {
    @SerialName("pending") PENDING("pending"),
    @SerialName("matched") MATCHED("matched"),
    @SerialName("unmatched") UNMATCHED("unmatched");

    companion object {
        fun fromWireValue(value: String?): MovieMatchStatus? = entries.firstOrNull { it.wireValue == value }
    }
}

/** How a movie got its TMDB id: from TMDB itself, the IMDb link, the title, the director or the user. */
enum class MovieMatchMethod(val wireValue: String) {
    TMDB("tmdb"),
    IMDB("imdb"),
    TITLE("title"),
    DIRECTOR("director"),
    MANUAL("manual");

    companion object {
        fun fromWireValue(value: String?): MovieMatchMethod? = entries.firstOrNull { it.wireValue == value }
    }
}
