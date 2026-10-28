package org.mobyle.data.local.auth

import kotlinx.datetime.Clock
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.mobyle.data.local.database.RefreshTokensTable
import org.mobyle.data.local.database.userDbIdOf

interface RefreshTokenDataSource {
    fun create(userExternalId: String): String
    fun validate(rawToken: String): String?
    fun revoke(rawToken: String)

    /** Owner of a token that exists but was already revoked (reuse after rotation). */
    fun ownerOfRevoked(rawToken: String): String?
    fun revokeAllForUser(userExternalId: String)

    /** Revokes the token only if it belongs to the user; used on logout. */
    fun revokeIfOwnedBy(rawToken: String, userExternalId: String)
}

class RefreshTokenDataSourceImpl : RefreshTokenDataSource {

    private val expirySeconds = (System.getenv("REFRESH_TOKEN_EXPIRY_DAYS")?.toLongOrNull() ?: 30L) * 24 * 3600

    override fun create(userExternalId: String): String {
        val rawToken = generateSecureToken()
        val tokenHash = sha256(rawToken)
        val expiresAt = System.currentTimeMillis() / 1000 + expirySeconds

        transaction {
            RefreshTokensTable.insert {
                it[RefreshTokensTable.userExternalId] = userExternalId
                it[RefreshTokensTable.userId] = userDbIdOf(userExternalId)
                it[RefreshTokensTable.tokenHash] = tokenHash
                it[RefreshTokensTable.expiresAt] = expiresAt
                it[RefreshTokensTable.createdAt] = Clock.System.now()
            }
        }

        return rawToken
    }

    override fun validate(rawToken: String): String? {
        val tokenHash = sha256(rawToken)
        val now = System.currentTimeMillis() / 1000

        return transaction {
            RefreshTokensTable.selectAll()
                .where {
                    (RefreshTokensTable.tokenHash eq tokenHash) and
                        (RefreshTokensTable.expiresAt greater now) and
                        RefreshTokensTable.revokedAt.isNull()
                }
                .firstOrNull()
                ?.get(RefreshTokensTable.userExternalId)
        }
    }

    override fun revoke(rawToken: String) {
        val tokenHash = sha256(rawToken)
        val now = System.currentTimeMillis() / 1000

        transaction {
            RefreshTokensTable.update({ RefreshTokensTable.tokenHash eq tokenHash }) {
                it[revokedAt] = now
            }
        }
    }

    override fun ownerOfRevoked(rawToken: String): String? {
        val tokenHash = sha256(rawToken)
        return transaction {
            RefreshTokensTable.selectAll()
                .where { (RefreshTokensTable.tokenHash eq tokenHash) and RefreshTokensTable.revokedAt.isNotNull() }
                .firstOrNull()
                ?.get(RefreshTokensTable.userExternalId)
        }
    }

    override fun revokeAllForUser(userExternalId: String) {
        val now = System.currentTimeMillis() / 1000
        transaction {
            RefreshTokensTable.update({
                (RefreshTokensTable.userExternalId eq userExternalId) and RefreshTokensTable.revokedAt.isNull()
            }) {
                it[revokedAt] = now
            }
        }
    }

    override fun revokeIfOwnedBy(rawToken: String, userExternalId: String) {
        val tokenHash = sha256(rawToken)
        val now = System.currentTimeMillis() / 1000
        transaction {
            RefreshTokensTable.update({
                (RefreshTokensTable.tokenHash eq tokenHash) and
                    (RefreshTokensTable.userExternalId eq userExternalId) and
                    RefreshTokensTable.revokedAt.isNull()
            }) {
                it[revokedAt] = now
            }
        }
    }

    private fun generateSecureToken(): String {
        val bytes = ByteArray(32)
        java.security.SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun sha256(input: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
}
