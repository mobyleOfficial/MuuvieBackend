package org.mobyle.domain.usecase.watch

import org.mobyle.domain.model.ConflictException
import org.mobyle.domain.model.NotFoundException
import org.mobyle.domain.model.WatchProgress
import org.mobyle.domain.repository.WatchRepository

/** "Assistir de novo" (F2.5): opens a new cycle, keeping the previous ones in the history. */
class StartRewatch(private val repository: WatchRepository) {
    operator fun invoke(userId: String, movieId: Long): WatchProgress {
        repository.getMovie(movieId) ?: throw NotFoundException("Movie not found")
        val userMovie = repository.getUserMovie(userId, movieId)
        if (userMovie == null || !userMovie.cycleCompleted) {
            throw ConflictException("cycle_not_completed", "Finish the current watch before starting a rewatch")
        }
        return repository.startRewatch(userId, movieId)
    }
}
