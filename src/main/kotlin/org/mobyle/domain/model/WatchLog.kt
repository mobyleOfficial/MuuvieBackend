package org.mobyle.domain.model

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import org.mobyle.domain.model.vocabulary.Emotion
import org.mobyle.domain.model.vocabulary.Rating
import org.mobyle.domain.model.vocabulary.Visibility
import org.mobyle.domain.model.vocabulary.WatchMode
import org.mobyle.domain.model.vocabulary.WatchStatus
import org.mobyle.domain.model.vocabulary.WatchVenue

/** A GIF picked from the search proxy. The url is kept so the log renders without the provider. */
@Serializable
data class Gif(val id: String, val url: String, val previewUrl: String? = null)

/** One watch session (F2.1). `cycle` is 1 on the first watch and grows with each rewatch. */
@Serializable
data class WatchLog(
    val id: Long,
    val userId: String,
    val movieId: Long,
    val watchedOn: LocalDate,
    val cycle: Int,
    val mode: WatchMode,
    val minutesWatched: Int?,
    val percent: Int?,
    val completed: Boolean,
    val venue: WatchVenue?,
    val providerName: String?,
    val emotions: List<Emotion>,
    val gif: Gif?,
    val comment: String?,
    val hasSpoiler: Boolean,
    val visibility: Visibility,
    val createdAt: Instant,
    val updatedAt: Instant,
    val self: Boolean = false
)

/** Fields of a watch log, already validated; minutes and percent are resolved against the runtime. */
data class WatchLogDraft(
    val watchedOn: LocalDate,
    val mode: WatchMode,
    val minutesWatched: Int?,
    val percent: Int?,
    val completed: Boolean,
    val venue: WatchVenue?,
    val providerName: String?,
    val emotions: List<Emotion>,
    val gif: Gif?,
    val comment: String?,
    val hasSpoiler: Boolean,
    val visibility: Visibility
)

/** Totals of a user's logs for one movie (F2.3). */
@Serializable
data class WatchLogSummary(
    val totalMinutes: Int,
    val sessions: Int,
    val rewatches: Int,
    val currentCycle: Int,
    val topEmotions: List<Emotion>
)

@Serializable
data class MovieLogHistory(val summary: WatchLogSummary, val logs: Page<WatchLog>)

/** A log of the profile's Registros tab (F5.2), with the movie it belongs to. */
@Serializable
data class DiaryEntry(val log: WatchLog, val movie: MovieCard)

/** Progress of the current cycle. */
@Serializable
data class WatchProgress(val cycle: Int, val minutes: Int?, val percent: Int?, val completed: Boolean)

/** The rating label of a movie with its review (phase 13). `id` is the comment target id. */
@Serializable
data class MovieRating(
    val id: Long,
    val rating: Rating,
    val review: String?,
    val hasSpoiler: Boolean,
    val ratedAt: Instant?
)

/** What the viewer has for a movie: the `me` block of GET /movies/{id}. [status] is null off both system shelves. */
@Serializable
data class MovieUserState(
    val shelfIds: List<Long>,
    val status: WatchStatus?,
    val progress: WatchProgress?,
    val rating: MovieRating?,
    val rewatches: Int,
    val watchingNow: Boolean
)

/** Row of user_movies as the watch use cases need it; null fields were never set. */
data class UserMovieRecord(
    val id: Long,
    val status: String,
    val rating: Rating?,
    val review: String?,
    val reviewHasSpoiler: Boolean,
    val ratedAt: Instant?,
    val rewatches: Int,
    val currentCycle: Int,
    val progressMinutes: Int?,
    val progressPercent: Int?
) {
    /** The current cycle is done: a completed log, or the movie on Já assisti before any rewatch. */
    val cycleCompleted: Boolean
        get() = progressPercent == 100 || (currentCycle == 1 && status == WatchStatus.WATCHED.wireValue)
}

/** The movie a user is watching now (F2.6), for the profile header. */
@Serializable
data class WatchingNow(
    val movieId: Long,
    val tmdbId: Int?,
    val title: String,
    val posterPath: String?,
    val runtime: Int?,
    val progress: WatchProgress?
)
