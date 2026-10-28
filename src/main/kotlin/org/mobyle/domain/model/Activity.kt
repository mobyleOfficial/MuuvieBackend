package org.mobyle.domain.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import org.mobyle.domain.model.vocabulary.ActivitySubject
import org.mobyle.domain.model.vocabulary.ActivityType
import org.mobyle.domain.model.vocabulary.Visibility
import java.util.Base64

/** Who did something, as the feed and the conversations show them. */
@Serializable
data class UserSummary(
    val id: String,
    val username: String,
    val handle: String?,
    val displayName: String?,
    val avatarUrl: String?,
    val accentColor: String?
)

/**
 * One item of the feed (F6.1) as the viewer sees it. [payload] is a snapshot taken when the
 * activity was published, so it renders without loading the subject: `movie`, `log`, `rating`,
 * `shelf`, `locadora` or `import` blocks, depending on the type. [mutual] is about the actor
 * and the viewer; [loves] counts the "amei" and [loved] says whether the viewer gave one.
 */
@Serializable
data class Activity(
    val id: Long,
    val type: ActivityType,
    val actor: UserSummary,
    val visibility: Visibility,
    val subjectType: ActivitySubject,
    val subjectId: Long,
    val payload: JsonObject,
    val loves: Int,
    val loved: Boolean,
    val self: Boolean,
    val mutual: Boolean,
    val hidden: Boolean,
    val createdAt: Instant
)

/** An activity to store, built by ActivityPublisher. */
data class ActivityDraft(
    val actorId: String,
    val type: ActivityType,
    val visibility: Visibility,
    val subjectType: ActivitySubject,
    val subjectId: Long,
    val movieId: Long?,
    val payload: JsonObject
)

/** A stored activity before the viewer-dependent parts, as the publisher needs it to merge. */
data class ActivityRecord(
    val id: Long,
    val actorId: String,
    val type: ActivityType,
    val visibility: Visibility,
    val subjectType: ActivitySubject,
    val subjectId: Long,
    val payload: JsonObject,
    val createdAt: Instant,
    val hidden: Boolean = false
)

/** Answer of PUT/DELETE /activities/{id}/love. */
@Serializable
data class ActivityLoves(val loves: Int, val loved: Boolean)

/**
 * Position in a list ordered by time then id, newest first: the last item of the page.
 * Opaque to the app (base64 of `instant|id`); the next page starts strictly after it, so
 * items inserted meanwhile never shift it.
 */
data class FeedCursor(val at: Instant, val id: Long) {
    fun encode(): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString("$at|$id".toByteArray())

    companion object {
        /** Null for a missing cursor; a malformed one is a validation error. */
        fun decode(value: String?): FeedCursor? {
            if (value.isNullOrBlank()) return null
            return runCatching {
                val (at, id) = String(Base64.getUrlDecoder().decode(value)).split("|", limit = 2)
                FeedCursor(Instant.parse(at), id.toLong())
            }.getOrElse { throw ValidationException("cursor", "Invalid cursor") }
        }
    }
}
