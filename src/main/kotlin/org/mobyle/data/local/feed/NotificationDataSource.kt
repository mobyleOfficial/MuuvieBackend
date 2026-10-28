package org.mobyle.data.local.feed

import kotlinx.datetime.Clock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.upsert
import org.mobyle.data.local.database.DeviceTokensTable
import org.mobyle.data.local.database.NotificationPrefsTable
import org.mobyle.data.local.database.userDbIdOf
import org.mobyle.domain.model.DeviceToken
import org.mobyle.domain.model.NotificationPrefs
import org.mobyle.domain.repository.NotificationRepository

/** Exposed implementation of push tokens and preferences; same contract as [NotificationRepository]. */
interface NotificationDataSource : NotificationRepository

class NotificationDataSourceImpl : NotificationDataSource {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override fun saveDevice(userId: String, device: DeviceToken) {
        transaction {
            val user = userDbIdOf(userId) ?: return@transaction
            // Keyed by the token: a phone that changes account moves to the new one.
            DeviceTokensTable.upsert(DeviceTokensTable.token) {
                it[DeviceTokensTable.userId] = user
                it[platform] = device.platform
                it[token] = device.token
                it[locale] = device.locale
                it[updatedAt] = Clock.System.now()
            }
        }
    }

    override fun removeDevice(userId: String, token: String) {
        transaction {
            val user = userDbIdOf(userId) ?: return@transaction
            DeviceTokensTable.deleteWhere { (DeviceTokensTable.userId eq user) and (DeviceTokensTable.token eq token) }
        }
    }

    override fun devices(userId: String): List<DeviceToken> = transaction {
        val user = userDbIdOf(userId) ?: return@transaction emptyList()
        DeviceTokensTable.selectAll()
            .where { DeviceTokensTable.userId eq user }
            .map { DeviceToken(it[DeviceTokensTable.platform], it[DeviceTokensTable.token], it[DeviceTokensTable.locale]) }
    }

    override fun removeTokens(tokens: Collection<String>) {
        if (tokens.isEmpty()) return
        transaction { DeviceTokensTable.deleteWhere { token inList tokens } }
    }

    override fun prefs(userId: String): NotificationPrefs = transaction {
        val user = userDbIdOf(userId) ?: return@transaction NotificationPrefs()
        NotificationPrefsTable.selectAll()
            .where { NotificationPrefsTable.userId eq user }
            .firstOrNull()
            ?.let { json.decodeFromString<NotificationPrefs>(it[NotificationPrefsTable.prefs]) }
            ?: NotificationPrefs()
    }

    override fun savePrefs(userId: String, prefs: NotificationPrefs) {
        transaction {
            val user = userDbIdOf(userId) ?: return@transaction
            NotificationPrefsTable.upsert {
                it[NotificationPrefsTable.userId] = user
                it[NotificationPrefsTable.prefs] = json.encodeToString(prefs)
                it[updatedAt] = Clock.System.now()
            }
        }
    }
}
