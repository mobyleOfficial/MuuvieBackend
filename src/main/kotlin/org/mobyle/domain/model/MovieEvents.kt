package org.mobyle.domain.model

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import org.mobyle.domain.model.vocabulary.Emotion
import org.mobyle.domain.model.vocabulary.Rating

/** Where a movie shows up in the Programação (F12.1): open maratonas and CineClubes. */
@Serializable
data class MovieEvents(
    val maratonas: List<MovieMaratona>,
    val cineclubes: List<CineClubeSummary>
)

/** A maratona with the challenges the movie completes by their criteria. */
@Serializable
data class MovieMaratona(
    val maratona: MaratonaSummary,
    val challenges: List<String>
)

/**
 * What the community did with a movie (B13.2). Only people whose Já assisti (ratings and
 * watched) or Quero ver is public count; [shelves] counts public custom shelves (D5) and
 * [emotions] is the full distribution of the public sessions, most frequent first (B13.5).
 */
@Serializable
data class MovieCommunity(
    val ratings: Map<Rating, Int> = emptyMap(),
    val watched: Int = 0,
    val wantToWatch: Int = 0,
    val shelves: Int = 0,
    val emotions: List<EmotionCount> = emptyList()
)

@Serializable
data class EmotionCount(val emotion: Emotion, val count: Int)

/** Activity of a movie per calendar month, oldest first, zeros included (B13.5). */
@Serializable
data class MovieTimeline(val months: List<MonthActivity>)

/** [month] is "YYYY-MM"; public sessions watched and public ratings given in it. */
@Serializable
data class MonthActivity(val month: String, val sessions: Int, val ratings: Int)

/** Public sessions (by watched day) and public ratings (by rating day, UTC) of a movie in a window. */
data class MovieActivity(
    val sessionsByDay: Map<LocalDate, Int> = emptyMap(),
    val ratingsByDay: Map<LocalDate, Int> = emptyMap()
)
