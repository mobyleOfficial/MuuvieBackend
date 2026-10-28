package org.mobyle.domain.usecase.watch

import org.mobyle.domain.model.NotFoundException
import org.mobyle.domain.repository.WatchRepository
import org.mobyle.domain.usecase.feed.ActivityPublisher

/** Removes the rating label and its review. Logs and shelves stay. */
class RemoveMovieRating(private val repository: WatchRepository, private val activities: ActivityPublisher) {
    operator fun invoke(userId: String, movieId: Long) {
        repository.getMovie(movieId) ?: throw NotFoundException("Movie not found")
        repository.removeRating(userId, movieId)
        activities.ratingRemoved(userId, movieId)
    }
}
