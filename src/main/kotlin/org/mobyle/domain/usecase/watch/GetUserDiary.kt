package org.mobyle.domain.usecase.watch

import org.mobyle.domain.model.DiaryEntry
import org.mobyle.domain.model.NotFoundException
import org.mobyle.domain.model.Page
import org.mobyle.domain.model.PageRequest
import org.mobyle.domain.policy.VisibilityPolicy
import org.mobyle.domain.repository.ShelfRepository
import org.mobyle.domain.repository.WatchRepository
import org.mobyle.domain.usecase.shelves.ShelfAccess

/** Every session of a user across movies, newest first (the profile's Registros tab, F5.2). */
class GetUserDiary(
    private val repository: WatchRepository,
    private val shelves: ShelfRepository,
    private val access: ShelfAccess
) {
    operator fun invoke(viewerId: String?, ownerId: String, page: PageRequest): Page<DiaryEntry> {
        if (!shelves.userExists(ownerId)) throw NotFoundException("User not found")
        val visibilities = VisibilityPolicy.visibleTo(access.relation(viewerId, ownerId))
        val entries = repository.getUserLogs(ownerId, visibilities, page)
        return entries.copy(results = entries.results.map { it.copy(log = it.log.copy(self = it.log.userId == viewerId)) })
    }
}
