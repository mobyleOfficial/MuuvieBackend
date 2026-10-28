package org.mobyle.domain.usecase.watch

import org.mobyle.domain.model.ForbiddenException
import org.mobyle.domain.model.NotFoundException
import org.mobyle.domain.model.WatchLog
import org.mobyle.domain.policy.VisibilityPolicy
import org.mobyle.domain.repository.WatchRepository
import org.mobyle.domain.usecase.shelves.ShelfAccess

/** Loads watch logs through the visibility rules (domain ADR, rule 7). */
class WatchLogAccess(private val repository: WatchRepository, private val access: ShelfAccess) {

    /** The log as [viewerId] sees it. A log the viewer cannot see does not exist for them. */
    fun visibleLog(viewerId: String?, logId: Long): WatchLog {
        val log = repository.getLog(logId) ?: throw NotFoundException("Watch log not found")
        if (!VisibilityPolicy.canView(access.relation(viewerId, log.userId), log.visibility)) {
            throw NotFoundException("Watch log not found")
        }
        return log.copy(self = log.userId == viewerId)
    }

    /** A log [userId] may change. */
    fun ownedLog(userId: String, logId: Long): WatchLog {
        val log = visibleLog(userId, logId)
        if (!log.self) throw ForbiddenException("not_log_owner", "Only the owner can change this watch log")
        return log
    }
}
