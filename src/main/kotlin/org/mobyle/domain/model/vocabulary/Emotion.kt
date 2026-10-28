package org.mobyle.domain.model.vocabulary

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Emotions a user can attach to a watch session (up to MAX_PER_LOG).
 *
 * `id` is persisted (`watch_logs.emotion_ids`) and must never change or be reused;
 * new emotions go at the end. Labels are nouns so they stay gender-neutral.
 */
@Serializable
enum class Emotion(val id: Int, val wireValue: String) {
    @SerialName("curiosity") CURIOSITY(0, "curiosity"),
    @SerialName("inspiration") INSPIRATION(1, "inspiration"),
    @SerialName("reflection") REFLECTION(2, "reflection"),
    @SerialName("relaxation") RELAXATION(3, "relaxation"),
    @SerialName("moved") MOVED(4, "moved"),
    @SerialName("nostalgia") NOSTALGIA(5, "nostalgia"),
    @SerialName("gratitude") GRATITUDE(6, "gratitude"),
    @SerialName("affection") AFFECTION(7, "affection"),
    @SerialName("surprise") SURPRISE(8, "surprise"),
    @SerialName("passion") PASSION(9, "passion"),
    @SerialName("fun") FUN(10, "fun"),
    @SerialName("joy") JOY(11, "joy"),
    @SerialName("humor") HUMOR(12, "humor"),
    @SerialName("freak_out") FREAK_OUT(13, "freak_out"),
    @SerialName("anxiety") ANXIETY(14, "anxiety"),
    @SerialName("distress") DISTRESS(15, "distress"),
    @SerialName("tension") TENSION(16, "tension"),
    @SerialName("distraction") DISTRACTION(17, "distraction"),
    @SerialName("confusion") CONFUSION(18, "confusion"),
    @SerialName("suspicion") SUSPICION(19, "suspicion"),
    @SerialName("irritation") IRRITATION(20, "irritation"),
    @SerialName("anger") ANGER(21, "anger"),
    @SerialName("indignation") INDIGNATION(22, "indignation"),
    @SerialName("fear") FEAR(23, "fear"),
    @SerialName("vulnerability") VULNERABILITY(24, "vulnerability"),
    @SerialName("embarrassment") EMBARRASSMENT(25, "embarrassment"),
    @SerialName("frustration") FRUSTRATION(26, "frustration"),
    @SerialName("sadness") SADNESS(27, "sadness"),
    @SerialName("melancholy") MELANCHOLY(28, "melancholy"),
    @SerialName("tiredness") TIREDNESS(29, "tiredness"),
    @SerialName("boredom") BOREDOM(30, "boredom"),
    @SerialName("connection") CONNECTION(31, "connection"),
    @SerialName("focus") FOCUS(32, "focus"),
    @SerialName("intensity") INTENSITY(33, "intensity"),
    @SerialName("enthusiasm") ENTHUSIASM(34, "enthusiasm"),
    @SerialName("excitement") EXCITEMENT(35, "excitement"),
    @SerialName("euphoria") EUPHORIA(36, "euphoria"),
    @SerialName("shock") SHOCK(37, "shock"),
    @SerialName("horror") HORROR(38, "horror"),
    @SerialName("disgust") DISGUST(39, "disgust"),
    @SerialName("devastation") DEVASTATION(40, "devastation"),
    @SerialName("grudge") GRUDGE(41, "grudge"),
    @SerialName("indifference") INDIFFERENCE(42, "indifference"),
    @SerialName("apathy") APATHY(43, "apathy");

    companion object {
        const val MAX_PER_LOG = 6

        fun fromId(id: Int): Emotion? = entries.firstOrNull { it.id == id }

        fun fromWireValue(value: String): Emotion? = entries.firstOrNull { it.wireValue == value }
    }
}
