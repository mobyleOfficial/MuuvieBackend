package org.mobyle.domain.usecase.profile

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.mobyle.domain.model.UserProfile
import org.mobyle.domain.repository.ProfileRepository
import kotlin.test.Test
import kotlin.test.assertEquals

class UpdateUserProfileTest {

    private val repository = mockk<ProfileRepository>(relaxed = true)
    private val updateUserProfile = UpdateUserProfile(repository)

    private fun profile(username: String, bio: String = "") =
        UserProfile(id = "user-1", photoUrl = "", username = username, bio = bio)

    @Test
    fun `saves the trimmed username when it is free`() {
        coEvery { repository.isUsernameTaken("ana", "user-1") } returns false

        val result = updateUserProfile(profile("  ana "))

        assertEquals(UpdateProfileResult.Updated, result)
        coVerify { repository.updateUserProfile(match { it.username == "ana" }) }
    }

    @Test
    fun `rejects a username used by someone else`() {
        coEvery { repository.isUsernameTaken("ana", "user-1") } returns true

        assertEquals(UpdateProfileResult.UsernameTaken, updateUserProfile(profile("ana")))
        coVerify(exactly = 0) { repository.updateUserProfile(any()) }
    }

    @Test
    fun `rejects blank or too long fields`() {
        assertEquals(UpdateProfileResult.Invalid("username"), updateUserProfile(profile(" ")))
        assertEquals(UpdateProfileResult.Invalid("username"), updateUserProfile(profile("a".repeat(31))))
        assertEquals(UpdateProfileResult.Invalid("bio"), updateUserProfile(profile("ana", bio = "b".repeat(501))))
    }
}
