package org.mobyle.domain.model.vocabulary

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The single rating of a movie (phase 13): a label picked by hand, never a number.
 * The declaration order is the order of the picker and of "sort by rating".
 */
@Serializable
enum class Rating(val wireValue: String) {
    // "Favoritado": replaces the old heart.
    @SerialName("favorite") FAVORITE("favorite"),
    // "Adorei"
    @SerialName("loved") LOVED("loved"),
    // "Gostei"
    @SerialName("liked") LIKED("liked"),
    // "Neutro"
    @SerialName("neutral") NEUTRAL("neutral"),
    // "Não foi pra mim"
    @SerialName("not_for_me") NOT_FOR_ME("not_for_me"),
    // "Abandonei": ends the open cycle without finishing it.
    @SerialName("abandoned") ABANDONED("abandoned"),
    // "Na geladeira": keeps the open cycle.
    @SerialName("on_hold") ON_HOLD("on_hold");

    /** A verdict on a finished movie; only these offer the 100% session. */
    val isVerdict: Boolean get() = this != ABANDONED && this != ON_HOLD

    companion object {
        fun fromWireValue(value: String): Rating? = entries.firstOrNull { it.wireValue == value }

        /** Stars of an import (0.5-5, or 0.5-10 above 5) mapped to the label of the old sentiment thresholds. */
        fun fromStars(value: Double): Rating {
            val stars = if (value > 5) value / 2 else value
            return when {
                stars >= 4.5 -> LOVED
                stars >= 3.5 -> LIKED
                stars >= 2.5 -> NEUTRAL
                else -> NOT_FOR_ME
            }
        }
    }
}
