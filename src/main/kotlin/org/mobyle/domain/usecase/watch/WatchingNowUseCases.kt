package org.mobyle.domain.usecase.watch

import org.mobyle.domain.model.NotFoundException
import org.mobyle.domain.model.WatchProgress
import org.mobyle.domain.model.WatchingNow
import org.mobyle.domain.repository.ShelfRepository
import org.mobyle.domain.repository.WatchRepository
import org.mobyle.domain.usecase.feed.ActivityPublisher

/** Sets or clears (null) the movie shown as "assistindo agora" on the profile (F2.6). */
class SetWatchingNow(private val repository: WatchRepository, private val activities: ActivityPublisher) {
    operator fun invoke(userId: String, movieId: Long?) {
        val movie = movieId?.let { repository.getMovie(it) ?: throw NotFoundException("Movie not found") }
        repository.setWatchingNow(userId, movieId)
        movie?.let { activities.watchingNow(userId, it) }
    }
}

/** The movie a user is watching now, with the minutes bar of the current cycle (F2.6). Public. */
class GetWatchingNow(private val repository: WatchRepository, private val shelves: ShelfRepository) {
    operator fun invoke(userId: String): WatchingNow? {
        if (!shelves.userExists(userId)) throw NotFoundException("User not found")
        val movieId = repository.watchingNow(userId) ?: return null
        val movie = repository.getMovie(movieId) ?: return null
        val progress = repository.getUserMovie(userId, movieId)?.let {
            WatchProgress(it.currentCycle, it.progressMinutes, it.progressPercent, it.cycleCompleted)
        }
        return WatchingNow(movie.id, movie.tmdbId, movie.title, movie.posterPath, movie.runtime, progress)
    }
}
