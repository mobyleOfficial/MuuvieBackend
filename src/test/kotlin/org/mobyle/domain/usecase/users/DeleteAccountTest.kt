package org.mobyle.domain.usecase.users

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.mobyle.domain.model.ConflictException
import org.mobyle.domain.model.NotFoundException
import org.mobyle.domain.model.SocialProvider
import org.mobyle.domain.repository.AccountRepository
import org.mobyle.domain.repository.AppleTokenRevoker
import org.mobyle.domain.repository.AuthRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DeleteAccountTest {

    private val accounts = mockk<AccountRepository>()
    private val apple = mockk<AppleTokenRevoker>()
    private val auth = mockk<AuthRepository>()
    private val deleteAccount = DeleteAccount(accounts, apple, auth)

    private fun given(providers: Set<SocialProvider>, appleConfigured: Boolean = true) {
        every { accounts.providers("ana") } returns providers
        every { accounts.delete("ana") } returns true
        every { apple.configured } returns appleConfigured
        coEvery { apple.revoke(any()) } returns true
        coEvery { auth.logoutUser(any(), any()) } returns Result.success(Unit)
    }

    @Test
    fun `a Google account is deleted and its access token revoked`() = runTest {
        given(setOf(SocialProvider.GOOGLE))

        deleteAccount("ana", "token", null, skipAppleRevocation = false)

        verify { accounts.delete("ana") }
        coVerify { auth.logoutUser("token", null) }
        coVerify(exactly = 0) { apple.revoke(any()) }
    }

    @Test
    fun `an Apple account needs a fresh authorization code`() = runTest {
        given(setOf(SocialProvider.APPLE))

        val error = assertFailsWith<ConflictException> { deleteAccount("ana", "token", null, skipAppleRevocation = false) }

        assertEquals("apple_reauth_required", error.code)
        verify(exactly = 0) { accounts.delete(any()) }
    }

    @Test
    fun `the Apple token is revoked before the account goes`() = runTest {
        given(setOf(SocialProvider.APPLE, SocialProvider.GOOGLE))

        deleteAccount("ana", "token", " code ", skipAppleRevocation = false)

        coVerify { apple.revoke("code") }
        verify { accounts.delete("ana") }
    }

    @Test
    fun `a failed revocation or a device without Sign in with Apple still deletes`() = runTest {
        given(setOf(SocialProvider.APPLE))
        coEvery { apple.revoke(any()) } returns false

        deleteAccount("ana", "token", "code", skipAppleRevocation = false)
        deleteAccount("ana", "token", null, skipAppleRevocation = true)

        verify(exactly = 2) { accounts.delete("ana") }
    }

    @Test
    fun `without an Apple key there is nothing to revoke`() = runTest {
        given(setOf(SocialProvider.APPLE), appleConfigured = false)

        deleteAccount("ana", "token", null, skipAppleRevocation = false)

        verify { accounts.delete("ana") }
    }

    @Test
    fun `an account that is gone is a 404`() = runTest {
        given(emptySet())
        every { accounts.delete("ana") } returns false

        assertFailsWith<NotFoundException> { deleteAccount("ana", "token", null, skipAppleRevocation = false) }
    }
}
