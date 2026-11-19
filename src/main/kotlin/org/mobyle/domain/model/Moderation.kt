package org.mobyle.domain.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/** What a report points at. The wire value is the `targetType` of `POST /reports`. */
enum class ReportTarget(val wireValue: String) {
    USER("user"),
    ACTIVITY("activity"),
    COMMENT("comment"),
    MESSAGE("message"),
    SHELF("shelf"),
    LOCADORA("locadora"),
    MARATONA("maratona"),
    CINECLUBE("cineclube"),
    WATCH_LOG("watch_log");

    companion object {
        fun fromWireValue(value: String?): ReportTarget? = entries.firstOrNull { it.wireValue == value }
    }
}

/** Why something was reported. */
enum class ReportReason(val wireValue: String) {
    SPAM("spam"),
    HARASSMENT("harassment"),
    HATE("hate"),
    SEXUAL("sexual"),
    VIOLENCE("violence"),
    IMPERSONATION("impersonation"),
    SPOILER("spoiler"),
    OTHER("other");

    companion object {
        fun fromWireValue(value: String?): ReportReason? = entries.firstOrNull { it.wireValue == value }
    }
}

/** A report as it is stored; [reportedUserId] is the account behind the content, when known. */
data class ReportDraft(
    val reporterId: String,
    val reportedUserId: String?,
    val target: ReportTarget,
    val targetId: String,
    val reason: ReportReason,
    val details: String?
)

@Serializable
data class ReportReceipt(val id: Long)

/** Whether the viewer blocks an account; the answer of block and unblock. */
@Serializable
data class BlockStatus(val blocked: Boolean)

/** An account the viewer blocked, newest block first. */
@Serializable
data class BlockedUser(val user: UserSummary, val blockedAt: Instant)
