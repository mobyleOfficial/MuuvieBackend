package org.mobyle.domain.usecase.users

import org.mobyle.domain.model.HandleAvailability
import org.mobyle.domain.repository.UserProfileRepository

/** Whether a handle can be used (F5.3). The viewer's own handle counts as available. */
class CheckHandleAvailability(private val repository: UserProfileRepository) {
    operator fun invoke(handle: String, viewerId: String?): HandleAvailability {
        val normalized = normalizeHandle(handle)
        return when {
            !isValidHandle(normalized) -> HandleAvailability(normalized, available = false, reason = REASON_INVALID)
            repository.isHandleTaken(normalized, exceptUserId = viewerId) ->
                HandleAvailability(normalized, available = false, reason = REASON_TAKEN)
            else -> HandleAvailability(normalized, available = true)
        }
    }

    private companion object {
        const val REASON_INVALID = "invalid"
        const val REASON_TAKEN = "taken"
    }
}
