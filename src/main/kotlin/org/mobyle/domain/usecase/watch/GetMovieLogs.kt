package org.mobyle.domain.usecase.watch

import org.mobyle.domain.model.MovieLogHistory
import org.mobyle.domain.model.NotFoundException
import org.mobyle.domain.model.PageRequest
import org.mobyle.domain.policy.VisibilityPolicy
import org.mobyle.domain.repository.ShelfRepository
import org.mobyle.domain.repository.WatchRepository
import org.mobyle.domain.usecase.shelves.ShelfAccess

/** A user's sessions of a movie with their summary (F2.3), limited to what the viewer can see. */
class GetMovieLogs(
    private val repository: WatchRepository,
    private val shelves: ShelfRepository,
    private val access: ShelfAccess
) {
    operator fun invoke(viewerId: String?, ownerId: String, movieId: Long, page: PageRequest): MovieLogHistory {
        if (!shelves.userExists(ownerId)) throw NotFoundException("User not found")
        val visibilities = VisibilityPolicy.visibleTo(access.relation(viewerId, ownerId))
        val logs = repository.getLogs(ownerId, movieId, visibilities, page)
        return MovieLogHistory(
            summary = repository.summary(ownerId, movieId, visibilities),
            logs = logs.copy(results = logs.results.map { it.copy(self = it.userId == viewerId) })
        )
    }
}
