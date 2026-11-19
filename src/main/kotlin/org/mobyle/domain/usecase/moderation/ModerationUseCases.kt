package org.mobyle.domain.usecase.moderation

import org.mobyle.domain.model.BlockStatus
import org.mobyle.domain.model.BlockedUser
import org.mobyle.domain.model.NotFoundException
import org.mobyle.domain.model.Page
import org.mobyle.domain.model.PageRequest
import org.mobyle.domain.model.ReportDraft
import org.mobyle.domain.model.ReportReason
import org.mobyle.domain.model.ReportReceipt
import org.mobyle.domain.model.ReportTarget
import org.mobyle.domain.model.TooManyRequestsException
import org.mobyle.domain.model.ValidationException
import org.mobyle.domain.repository.FollowRepository
import org.mobyle.domain.repository.ModerationRepository
import org.mobyle.domain.repository.RateLimiter
import org.mobyle.domain.repository.ReportNotifier
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("Moderation")

/** At most 20 reports per hour per user. */
private const val REPORTS_PER_HOUR = 20
private const val HOUR_MS = 60 * 60_000L
const val REPORT_DETAILS_MAX_LENGTH = 1000

/**
 * Blocks an account. Idempotent. The follows between both are removed, neither can follow the
 * other again, and each stops seeing the other's profile, activities, comments and conversation.
 */
class BlockUser(private val moderation: ModerationRepository, private val follows: FollowRepository) {
    operator fun invoke(userId: String, targetId: String): BlockStatus {
        if (userId == targetId) throw ValidationException("userId", "Cannot block yourself")
        follows.userSummary(targetId) ?: throw NotFoundException("User not found")
        moderation.block(userId, targetId)
        return BlockStatus(blocked = true)
    }
}

/** Removes a block. Idempotent; follows removed by the block do not come back. */
class UnblockUser(private val moderation: ModerationRepository) {
    operator fun invoke(userId: String, targetId: String): BlockStatus {
        moderation.unblock(userId, targetId)
        return BlockStatus(blocked = false)
    }
}

/** The accounts the user blocked, for Configurações › Contas bloqueadas. */
class GetBlockedUsers(private val moderation: ModerationRepository) {
    operator fun invoke(userId: String, page: PageRequest): Page<BlockedUser> = moderation.blockedUsers(userId, page)
}

/**
 * Reports an account or a piece of content to the team. The target must exist; reporting your
 * own content is allowed (it is harmless and saves a lookup), reporting yourself as a user is not.
 */
class ReportContent(
    private val moderation: ModerationRepository,
    private val rateLimiter: RateLimiter,
    private val notifier: ReportNotifier
) {
    operator fun invoke(userId: String, targetType: String, targetId: String, reason: String, details: String?): ReportReceipt {
        val target = ReportTarget.fromWireValue(targetType)
            ?: throw ValidationException("targetType", "Unknown targetType $targetType")
        val why = ReportReason.fromWireValue(reason) ?: throw ValidationException("reason", "Unknown reason $reason")
        val id = targetId.trim()
        if (id.isEmpty() || id.length > 100) throw ValidationException("targetId", "targetId is required")
        val text = details?.trim()?.takeIf { it.isNotEmpty() }
        if (text != null && text.length > REPORT_DETAILS_MAX_LENGTH) {
            throw ValidationException("details", "details must have at most $REPORT_DETAILS_MAX_LENGTH characters")
        }
        val owner = moderation.ownerOf(target, id) ?: throw NotFoundException("Nothing to report")
        if (target == ReportTarget.USER && owner == userId) throw ValidationException("targetId", "Cannot report yourself")
        if (!rateLimiter.tryAcquire("report:$userId", REPORTS_PER_HOUR, HOUR_MS)) {
            throw TooManyRequestsException("rate_limited", "Too many reports, try again later")
        }
        val draft = ReportDraft(userId, owner, target, id, why, text)
        val reportId = moderation.report(draft)
        log.warn("Report $reportId: ${target.wireValue} $id (${why.wireValue}) by $userId against $owner")
        runCatching { notifier.reported(reportId, draft) }
            .onFailure { log.error("Could not notify report $reportId: ${it.message}") }
        return ReportReceipt(reportId)
    }
}
