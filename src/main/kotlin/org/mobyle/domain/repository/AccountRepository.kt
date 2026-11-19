package org.mobyle.domain.repository

import org.mobyle.domain.model.SocialProvider

/** The account as a whole: what it signs in with, and deleting it. Users are identified by their external id. */
interface AccountRepository {
    fun providers(userId: String): Set<SocialProvider>

    /**
     * Deletes the account and everything it made (sessions, logs, ratings, shelves, locadoras,
     * activities, comments, conversations, maratonas and CineClubes it owns…). False when there was
     * no such account.
     */
    fun delete(userId: String): Boolean
}

/**
 * Revokes the app's Apple tokens of an account (required by Apple when a Sign in with Apple
 * account is deleted). Apple only gives the token for a fresh authorization code, so the app signs
 * in with Apple again before deleting.
 */
interface AppleTokenRevoker {
    /** False while the server has no Apple key (APPLE_TEAM_ID, APPLE_KEY_ID, APPLE_PRIVATE_KEY). */
    val configured: Boolean

    /** True when Apple accepted the code and revoked the token. */
    suspend fun revoke(authorizationCode: String): Boolean
}
