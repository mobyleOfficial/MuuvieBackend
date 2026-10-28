package org.mobyle.domain.usecase.notifications

import org.mobyle.domain.model.DevicePlatforms
import org.mobyle.domain.model.DeviceToken
import org.mobyle.domain.model.NotificationPrefs
import org.mobyle.domain.model.ValidationException
import org.mobyle.domain.repository.NotificationRepository

private const val TOKEN_MAX_LENGTH = 500

/** Registers the push token of a device (F6.7); the app calls it on login and when the token rotates. */
class RegisterDevice(private val repository: NotificationRepository) {
    operator fun invoke(userId: String, platform: String, token: String, locale: String?) {
        if (platform !in DevicePlatforms.ALL) throw ValidationException("platform", "Unknown platform")
        val value = token.trim()
        if (value.isEmpty() || value.length > TOKEN_MAX_LENGTH) throw ValidationException("token", "Invalid token")
        repository.saveDevice(userId, DeviceToken(platform, value, locale?.trim()?.take(10)?.takeIf { it.isNotEmpty() }))
    }
}

/** Forgets a device, on logout or when the user turns push off on the phone. */
class RemoveDevice(private val repository: NotificationRepository) {
    operator fun invoke(userId: String, token: String) = repository.removeDevice(userId, token.trim())
}

class GetNotificationPrefs(private val repository: NotificationRepository) {
    operator fun invoke(userId: String): NotificationPrefs = repository.prefs(userId)
}

class SaveNotificationPrefs(private val repository: NotificationRepository) {
    operator fun invoke(userId: String, prefs: NotificationPrefs): NotificationPrefs {
        repository.savePrefs(userId, prefs)
        return repository.prefs(userId)
    }
}
