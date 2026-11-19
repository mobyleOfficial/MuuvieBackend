package org.mobyle.domain.usecase.users

import org.mobyle.domain.model.ConflictException
import org.mobyle.domain.model.NotFoundException
import org.mobyle.domain.model.SocialProvider
import org.mobyle.domain.repository.AccountRepository
import org.mobyle.domain.repository.AppleTokenRevoker
import org.mobyle.domain.repository.AuthRepository
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("DeleteAccount")

/**
 * Deletes the signed-in account and everything it made (App Store 5.1.1(v), Google Play's account
 * deletion policy). An account that signs in with Apple has its Apple token revoked first: the app
 * signs in with Apple again and sends the fresh [appleAuthorizationCode]. Without one the answer is
 * 409 `apple_reauth_required`, unless [skipAppleRevocation] (the app runs where Sign in with Apple
 * is not available, such as Android).
 */
class DeleteAccount(
    private val accounts: AccountRepository,
    private val appleRevoker: AppleTokenRevoker,
    private val auth: AuthRepository
) {
    suspend operator fun invoke(
        userId: String,
        accessToken: String,
        appleAuthorizationCode: String?,
        skipAppleRevocation: Boolean
    ) {
        val providers = accounts.providers(userId)
        if (SocialProvider.APPLE in providers && appleRevoker.configured) {
            val code = appleAuthorizationCode?.trim()?.takeIf { it.isNotEmpty() }
            when {
                code != null -> if (!appleRevoker.revoke(code)) {
                    // The account is deleted anyway: the user can still remove the app in their Apple ID settings.
                    log.warn("Could not revoke the Apple token of user $userId")
                }
                !skipAppleRevocation -> throw ConflictException(
                    "apple_reauth_required",
                    "Sign in with Apple again to delete this account"
                )
                else -> log.info("Deleting user $userId without revoking Apple (no Sign in with Apple on this device)")
            }
        }
        if (!accounts.delete(userId)) throw NotFoundException("User not found")
        auth.logoutUser(accessToken, null)
        log.info("Deleted user $userId")
    }
}
