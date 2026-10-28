package org.mobyle.domain.repository

import org.mobyle.domain.model.DeviceToken
import org.mobyle.domain.model.NotificationPrefs

/** Push tokens and notification preferences (F6.7). */
interface NotificationRepository {
    /** Saves the token for [userId]; a token that belonged to someone else moves to them. */
    fun saveDevice(userId: String, device: DeviceToken)
    fun removeDevice(userId: String, token: String)
    fun devices(userId: String): List<DeviceToken>
    /** Drops tokens the push provider said are no longer valid. */
    fun removeTokens(tokens: Collection<String>)
    fun prefs(userId: String): NotificationPrefs
    fun savePrefs(userId: String, prefs: NotificationPrefs)
}
