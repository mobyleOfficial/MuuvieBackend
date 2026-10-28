package org.mobyle.domain.usecase.watch

import org.mobyle.domain.model.NotFoundException
import org.mobyle.domain.model.WatchLog
import org.mobyle.domain.repository.WatchRepository
import org.mobyle.domain.usecase.feed.ActivityPublisher

/** Edits a session; the cycle it belongs to does not change (F2.3). */
class UpdateWatchLog(
    private val repository: WatchRepository,
    private val access: WatchLogAccess,
    private val activities: ActivityPublisher
) {
    operator fun invoke(userId: String, logId: Long, input: WatchLogInput): WatchLog {
        val log = access.ownedLog(userId, logId)
        val movie = repository.getMovie(log.movieId) ?: throw NotFoundException("Movie not found")
        val draft = watchLogDraftOf(input, movie.runtime, today())
        val updated = repository.updateLog(logId, draft)
        activities.sessionChanged(userId, updated)
        return updated.copy(self = true)
    }
}
