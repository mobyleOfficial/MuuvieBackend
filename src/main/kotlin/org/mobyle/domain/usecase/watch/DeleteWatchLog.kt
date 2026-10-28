package org.mobyle.domain.usecase.watch

import org.mobyle.domain.repository.WatchRepository
import org.mobyle.domain.usecase.feed.ActivityPublisher

/** Deletes a session and recomputes the progress. The movie stays on Já assisti: removing it is up to the user. */
class DeleteWatchLog(
    private val repository: WatchRepository,
    private val access: WatchLogAccess,
    private val activities: ActivityPublisher
) {
    operator fun invoke(userId: String, logId: Long) {
        val log = access.ownedLog(userId, logId)
        repository.deleteLog(logId)
        activities.sessionDeleted(userId, log.movieId, logId)
    }
}
