package org.mobyle.domain.repository

import org.mobyle.domain.model.BlockedUser
import org.mobyle.domain.model.Page
import org.mobyle.domain.model.PageRequest
import org.mobyle.domain.model.ReportDraft
import org.mobyle.domain.model.ReportTarget

/** Blocks between users and reports of content. Users are identified by their external id. */
interface ModerationRepository {
    /** Blocks [blockedId] and removes the follows between both, in one transaction. False when it was blocked already. */
    fun block(blockerId: String, blockedId: String): Boolean

    /** False when there was no block to undo. */
    fun unblock(blockerId: String, blockedId: String): Boolean

    /** Whether [blockerId] blocks [blockedId]. */
    fun blocks(blockerId: String, blockedId: String): Boolean

    /** Whether either user blocks the other. */
    fun blockedEitherWay(userId: String, otherId: String): Boolean

    /** The external ids of everyone [userId] blocks or is blocked by. */
    fun blockedIds(userId: String): Set<String>

    fun blockedUsers(userId: String, page: PageRequest): Page<BlockedUser>

    fun report(draft: ReportDraft): Long

    /** The external id of the account behind [targetId], or null when the target does not exist. */
    fun ownerOf(target: ReportTarget, targetId: String): String?
}

/** Tells the team a report came in (a chat webhook, for instance). It never fails the report. */
fun interface ReportNotifier {
    fun reported(id: Long, draft: ReportDraft)
}
