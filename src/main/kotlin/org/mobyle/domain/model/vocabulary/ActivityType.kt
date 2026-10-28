package org.mobyle.domain.model.vocabulary

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What an activity of the feed says the user did (F6.1), adapted from the 14 types of the
 * Maratona plus the import. Maratonas, CineClubes and the bichinho publish from phases 7, 8 and 10.
 */
@Serializable
enum class ActivityType(val wireValue: String) {
    @SerialName("registro_sessao") WATCH_SESSION("registro_sessao"),
    @SerialName("avaliacao") RATING("avaliacao"),
    @SerialName("concluiu_filme") FINISHED_MOVIE("concluiu_filme"),
    @SerialName("nova_estante") NEW_SHELF("nova_estante"),
    @SerialName("estante_destaque") FEATURED_SHELF("estante_destaque"),
    @SerialName("nova_locadora") NEW_LOCADORA("nova_locadora"),
    @SerialName("entrou_maratona") JOINED_MARATONA("entrou_maratona"),
    @SerialName("concluiu_desafio") FINISHED_CHALLENGE("concluiu_desafio"),
    @SerialName("concluiu_maratona") FINISHED_MARATONA("concluiu_maratona"),
    @SerialName("entrou_cineclube") JOINED_CINECLUBE("entrou_cineclube"),
    @SerialName("criou_maratona") CREATED_MARATONA("criou_maratona"),
    @SerialName("criou_cineclube") CREATED_CINECLUBE("criou_cineclube"),
    @SerialName("desbloquear_bichinho") UNLOCKED_BICHINHO("desbloquear_bichinho"),
    @SerialName("assistindo_agora") WATCHING_NOW("assistindo_agora"),
    @SerialName("importou_filmes") IMPORTED_MOVIES("importou_filmes");

    companion object {
        fun fromWireValue(value: String): ActivityType? = entries.firstOrNull { it.wireValue == value }
    }
}

/** What an activity points to. Sessions, ratings and "watching now" point to the movie. */
@Serializable
enum class ActivitySubject(val wireValue: String) {
    @SerialName("movie") MOVIE("movie"),
    @SerialName("shelf") SHELF("shelf"),
    @SerialName("locadora") LOCADORA("locadora"),
    @SerialName("import") IMPORT("import"),
    @SerialName("maratona") MARATONA("maratona"),
    @SerialName("cineclube") CINECLUBE("cineclube"),
    @SerialName("bichinho") BICHINHO("bichinho");

    companion object {
        fun fromWireValue(value: String): ActivitySubject? = entries.firstOrNull { it.wireValue == value }
    }
}

/** Which slice of the feed to read (F6.2). */
enum class FeedScope(val wireValue: String) {
    // People I follow and me.
    ALL("all"),
    FOLLOWING("following"),
    ME("me");

    companion object {
        fun fromWireValue(value: String): FeedScope? = entries.firstOrNull { it.wireValue == value }
    }
}

/** Filter of the movie's activities (B13.3): Avaliações or Atualizações; null is Tudo. */
enum class MovieActivityKind(val wireValue: String) {
    // Anything that carries a rating block, alone or merged with a session.
    RATINGS("ratings"),
    // Sessions and "assistindo agora".
    UPDATES("updates");

    companion object {
        fun fromWireValue(value: String): MovieActivityKind? = entries.firstOrNull { it.wireValue == value }
    }
}
