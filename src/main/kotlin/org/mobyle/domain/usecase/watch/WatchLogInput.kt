package org.mobyle.domain.usecase.watch

import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.todayIn
import kotlinx.serialization.Serializable
import org.mobyle.domain.model.Gif
import org.mobyle.domain.model.ValidationException
import org.mobyle.domain.model.WatchLogDraft
import org.mobyle.domain.model.vocabulary.Emotion
import org.mobyle.domain.model.vocabulary.Visibility
import org.mobyle.domain.model.vocabulary.WatchMode
import org.mobyle.domain.model.vocabulary.WatchVenue

// Upper bound for minutes when TMDB has no runtime for the movie.
private const val UNKNOWN_RUNTIME_MAX_MINUTES = 600
private const val COMMENT_MAX_LENGTH = 5000
private const val PROVIDER_MAX_LENGTH = 255
private const val GIF_ID_MAX_LENGTH = 100
private const val GIF_URL_MAX_LENGTH = 1000

/** Body of POST /movies/{id}/logs and PUT /logs/{id} (F2.1). Only the date is needed for "Já assisti". */
@Serializable
data class WatchLogInput(
    val watchedOn: String? = null,
    val mode: String? = null,
    val minutesWatched: Int? = null,
    val percent: Int? = null,
    val venue: String? = null,
    val providerName: String? = null,
    val emotions: List<String> = emptyList(),
    val gif: Gif? = null,
    val comment: String? = null,
    val hasSpoiler: Boolean = false,
    val visibility: String? = null
)

private data class Progress(val minutes: Int?, val percent: Int?, val completed: Boolean)

/** The server's today; a log dated one day ahead is still accepted, for users east of UTC. */
fun today(): LocalDate = Clock.System.todayIn(TimeZone.UTC)

/**
 * Validates a watch log and resolves minutes, percent and completion against the movie
 * runtime (B2): minutes cannot pass the runtime, or 600 when it is unknown.
 */
fun watchLogDraftOf(input: WatchLogInput, runtime: Int?, today: LocalDate): WatchLogDraft {
    val knownRuntime = runtime?.takeIf { it > 0 }
    val watchedOn = input.watchedOn?.let {
        runCatching { LocalDate.parse(it) }.getOrNull() ?: throw ValidationException("watchedOn", "watchedOn must be YYYY-MM-DD")
    } ?: today
    if (watchedOn > today.plus(1, DateTimeUnit.DAY)) {
        throw ValidationException("watchedOn", "watchedOn cannot be in the future")
    }
    val mode = input.mode?.let {
        WatchMode.fromWireValue(it) ?: throw ValidationException("mode", "mode must be watched, minutes or percent")
    } ?: WatchMode.WATCHED

    val progress = when (mode) {
        WatchMode.WATCHED -> Progress(minutes = knownRuntime, percent = 100, completed = true)
        WatchMode.MINUTES -> {
            val maxMinutes = knownRuntime ?: UNKNOWN_RUNTIME_MAX_MINUTES
            val minutes = input.minutesWatched ?: throw ValidationException("minutesWatched", "minutesWatched is required")
            if (minutes !in 0..maxMinutes) {
                throw ValidationException("minutesWatched", "minutesWatched must be between 0 and $maxMinutes")
            }
            Progress(minutes, knownRuntime?.let { minutes * 100 / it }, completed = minutes == knownRuntime)
        }
        WatchMode.PERCENT -> {
            val percent = input.percent ?: throw ValidationException("percent", "percent is required")
            if (percent !in 0..100) throw ValidationException("percent", "percent must be between 0 and 100")
            Progress(knownRuntime?.let { it * percent / 100 }, percent, completed = percent == 100)
        }
    }

    val emotions = input.emotions.distinct().map {
        Emotion.fromWireValue(it) ?: throw ValidationException("emotions", "Unknown emotion $it")
    }
    if (emotions.size > Emotion.MAX_PER_LOG) {
        throw ValidationException("emotions", "At most ${Emotion.MAX_PER_LOG} emotions per session")
    }
    val venue = input.venue?.let {
        WatchVenue.fromWireValue(it) ?: throw ValidationException("venue", "Unknown venue $it")
    }
    val providerName = input.providerName?.trim()?.takeIf { it.isNotEmpty() }
    if (providerName != null && providerName.length > PROVIDER_MAX_LENGTH) {
        throw ValidationException("providerName", "providerName must have at most $PROVIDER_MAX_LENGTH characters")
    }
    val comment = input.comment?.trim()?.takeIf { it.isNotEmpty() }
    if (comment != null && comment.length > COMMENT_MAX_LENGTH) {
        throw ValidationException("comment", "comment must have at most $COMMENT_MAX_LENGTH characters")
    }
    val gif = input.gif?.also {
        if (it.id.isBlank() || it.id.length > GIF_ID_MAX_LENGTH || !it.url.startsWith("https://") ||
            it.url.length > GIF_URL_MAX_LENGTH
        ) {
            throw ValidationException("gif", "Invalid gif")
        }
    }
    val visibility = input.visibility?.let {
        Visibility.fromWireValue(it) ?: throw ValidationException("visibility", "Visibility must be public, mutual or secret")
    } ?: Visibility.DEFAULT

    return WatchLogDraft(
        watchedOn = watchedOn,
        mode = mode,
        minutesWatched = progress.minutes,
        percent = progress.percent,
        completed = progress.completed,
        venue = venue,
        providerName = providerName,
        emotions = emotions,
        gif = gif?.copy(previewUrl = null),
        comment = comment,
        hasSpoiler = input.hasSpoiler,
        visibility = visibility
    )
}
