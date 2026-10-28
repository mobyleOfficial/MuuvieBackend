package org.mobyle.domain.usecase.watch

import kotlinx.serialization.Serializable
import org.mobyle.domain.model.MovieRating
import org.mobyle.domain.model.NotFoundException
import org.mobyle.domain.model.ValidationException
import org.mobyle.domain.model.vocabulary.Rating
import org.mobyle.domain.repository.WatchRepository
import org.mobyle.domain.usecase.feed.ActivityPublisher

private const val REVIEW_MAX_LENGTH = 10000

/**
 * Body of PUT /movies/{id}/rating (B13.1). Emotions and visibility only apply to the 100%
 * session created with [createLog]; the rating itself is as visible as the user's Já assisti.
 */
@Serializable
data class RatingInput(
    val rating: String,
    val review: String? = null,
    val hasSpoiler: Boolean = false,
    val emotions: List<String> = emptyList(),
    val createLog: Boolean = false,
    val visibility: String? = null
)

/**
 * Rates a movie with one label. The shelves stay as they are: only the optional 100% session
 * puts the movie on Já assisti, and Abandonei or Na geladeira cannot create it. Abandonei ends
 * the open cycle, so the movie stops being "assistindo agora".
 */
class SetMovieRating(private val repository: WatchRepository, private val activities: ActivityPublisher) {
    operator fun invoke(userId: String, movieId: Long, input: RatingInput): MovieRating {
        val label = Rating.fromWireValue(input.rating)
            ?: throw ValidationException("rating", "rating must be one of ${Rating.entries.joinToString { it.wireValue }}")
        if (input.createLog && !label.isVerdict) {
            throw ValidationException("createLog", "createLog is not allowed with ${label.wireValue}")
        }
        val review = input.review?.trim()?.takeIf { it.isNotEmpty() }
        if (review != null && review.length > REVIEW_MAX_LENGTH) {
            throw ValidationException("review", "review must have at most $REVIEW_MAX_LENGTH characters")
        }
        val movie = repository.getMovie(movieId) ?: throw NotFoundException("Movie not found")
        val logDraft = if (input.createLog) {
            watchLogDraftOf(WatchLogInput(emotions = input.emotions, visibility = input.visibility), movie.runtime, today())
        } else null
        val rating = repository.setRating(userId, movieId, label, review, input.hasSpoiler)
        if (label == Rating.ABANDONED && repository.watchingNow(userId) == movieId) {
            repository.setWatchingNow(userId, null)
        }
        activities.movieRated(userId, movie, rating)
        logDraft?.let { activities.sessionLogged(userId, movie, repository.createLog(userId, movieId, it)) }
        return rating
    }
}
