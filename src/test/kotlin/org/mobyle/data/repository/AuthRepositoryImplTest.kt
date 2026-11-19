package org.mobyle.data.repository

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.mobyle.data.local.auth.RefreshTokenDataSource
import org.mobyle.data.local.auth.TokenBlocklistDataSource
import org.mobyle.data.local.auth.UserIdentityDataSource
import org.mobyle.data.local.database.TokenBlocklistTable
import org.mobyle.data.local.user.UserDatabaseDataSource
import org.mobyle.data.local.user.UserLocalDataSource
import org.mobyle.data.remote.auth.JWTUtil
import org.mobyle.data.remote.auth.social.SocialTokenVerifier
import org.mobyle.domain.model.ConflictException
import org.mobyle.domain.model.SocialIdentity
import org.mobyle.domain.model.SocialProvider
import org.mobyle.domain.model.User
import org.mobyle.domain.model.ValidationException
import org.mobyle.domain.repository.UserRepository
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthRepositoryImplTest {

    private val socialTokenVerifier = mockk<SocialTokenVerifier>()
    private val userIdentityDataSource = mockk<UserIdentityDataSource>(relaxed = true)
    private val userRepository = mockk<UserRepository>()
    private val userDatabaseDataSource = mockk<UserDatabaseDataSource>(relaxed = true)
    private val tokenBlocklistDataSource = TokenBlocklistDataSource()
    private val userLocalDataSource = mockk<UserLocalDataSource>(relaxed = true)
    private val refreshTokenDataSource = mockk<RefreshTokenDataSource>(relaxed = true)
    private val jwtUtil = JWTUtil(
        jwtSecret = "test-secret-key-with-minimum-32-characters-long",
        jwtExpirySeconds = 3600
    )

    private val repository = AuthRepositoryImpl(
        socialTokenVerifier = socialTokenVerifier,
        userIdentityDataSource = userIdentityDataSource,
        userRepository = userRepository,
        jwtUtil = jwtUtil,
        userDatabaseDataSource = userDatabaseDataSource,
        tokenBlocklistDataSource = tokenBlocklistDataSource,
        userLocalDataSource = userLocalDataSource,
        refreshTokenDataSource = refreshTokenDataSource
    )

    private val existingUser = User(
        id = "user-123",
        email = "ana@example.com",
        username = "ana",
        createdAt = "2026-07-22T10:00:00Z"
    )

    private fun identity(email: String? = "ana@example.com", verified: Boolean = true) = SocialIdentity(
        provider = SocialProvider.GOOGLE,
        subject = "google-sub-1",
        email = email,
        emailVerified = verified,
        picture = "https://example.com/ana.png"
    )

    @BeforeTest
    fun connectDatabase() {
        // TokenBlocklistDataSource is backed by a table.
        Database.connect("jdbc:h2:mem:auth_repository;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        transaction {
            SchemaUtils.drop(TokenBlocklistTable)
            SchemaUtils.create(TokenBlocklistTable)
        }
        every { refreshTokenDataSource.create(any()) } returns "refresh-token"
    }

    @Test
    fun `a linked provider account signs in to its account`() = runTest {
        val identity = identity()
        coEvery { socialTokenVerifier.verify(SocialProvider.GOOGLE, "id-token", null) } returns identity
        every { userIdentityDataSource.findUserExternalId(identity) } returns "user-123"
        every { userDatabaseDataSource.findByExternalId("user-123") } returns existingUser

        val token = repository.signInWithProvider(SocialProvider.GOOGLE, "id-token", null)

        assertEquals("user-123", token.user.id)
        assertFalse(token.isNewUser)
        assertEquals("refresh-token", token.refreshToken)
        assertEquals("user-123", jwtUtil.validateToken(token.accessToken).getOrThrow().userId)
        verify(exactly = 0) { userDatabaseDataSource.save(any()) }
        verify(exactly = 0) { userIdentityDataSource.link(any(), any()) }
    }

    @Test
    fun `a verified e-mail of an existing account links the provider account to it`() = runTest {
        val identity = identity()
        coEvery { socialTokenVerifier.verify(any(), any(), any()) } returns identity
        every { userIdentityDataSource.findUserExternalId(identity) } returns null
        every { userDatabaseDataSource.findByEmail("ana@example.com") } returns existingUser

        val token = repository.signInWithProvider(SocialProvider.GOOGLE, "id-token", null)

        assertEquals("user-123", token.user.id)
        assertFalse(token.isNewUser)
        verify { userIdentityDataSource.link("user-123", identity) }
        verify(exactly = 0) { userDatabaseDataSource.save(any()) }
    }

    @Test
    fun `an unverified e-mail never links to an existing account`() = runTest {
        val identity = identity(verified = false)
        coEvery { socialTokenVerifier.verify(any(), any(), any()) } returns identity
        every { userIdentityDataSource.findUserExternalId(identity) } returns null
        every { userDatabaseDataSource.findByUsername(any()) } returns emptyList()

        val token = repository.signInWithProvider(SocialProvider.GOOGLE, "id-token", null)

        assertTrue(token.isNewUser)
        verify(exactly = 0) { userDatabaseDataSource.findByEmail(any()) }
    }

    @Test
    fun `a new provider account creates an account with a generated nickname to confirm`() = runTest {
        val identity = identity(email = null, verified = false)
        coEvery { socialTokenVerifier.verify(any(), any(), any()) } returns identity
        every { userIdentityDataSource.findUserExternalId(identity) } returns null
        every { userDatabaseDataSource.findByUsername(any()) } returns emptyList()
        val saved = slot<User>()
        every { userDatabaseDataSource.save(capture(saved)) } answers { saved.captured }

        val token = repository.signInWithProvider(SocialProvider.APPLE, "id-token", "nonce")

        assertTrue(token.isNewUser)
        assertFalse(token.user.nicknameConfirmed)
        assertFalse(saved.captured.nicknameConfirmed)
        assertEquals("", saved.captured.email)
        assertEquals("https://example.com/ana.png", saved.captured.avatar)
        assertTrue(saved.captured.username.matches(Regex("[a-z0-9]+_[a-z0-9]+_\\d+")))
        verify { userIdentityDataSource.link(saved.captured.id, identity) }
    }

    @Test
    fun `chooseNickname trims and confirms the nickname`() = runTest {
        every { userDatabaseDataSource.findByExternalId("user-123") } returns existingUser
        every { userDatabaseDataSource.isUsernameTaken("Ana Clara", "user-123") } returns false

        assertEquals("Ana Clara", repository.chooseNickname("user-123", "  Ana Clara "))
        verify { userDatabaseDataSource.confirmNickname("user-123", "Ana Clara") }
    }

    @Test
    fun `chooseNickname rejects a nickname in use`() = runTest {
        every { userDatabaseDataSource.findByExternalId("user-123") } returns existingUser
        every { userDatabaseDataSource.isUsernameTaken("joao", "user-123") } returns true

        val error = assertFailsWith<ConflictException> { repository.chooseNickname("user-123", "joao") }
        assertEquals("nickname_already_exists", error.code)
    }

    @Test
    fun `chooseNickname rejects blank, long and JSON-breaking nicknames`() = runTest {
        listOf("  ", "a".repeat(31), "ana\"", "ana\\", "an\na").forEach { nickname ->
            assertFailsWith<ValidationException> { repository.chooseNickname("user-123", nickname) }
        }
        verify(exactly = 0) { userDatabaseDataSource.confirmNickname(any(), any()) }
    }

    @Test
    fun `logoutUser revokes valid token`() = runTest {
        val token = jwtUtil.generateToken(existingUser)

        val result = repository.logoutUser(token)

        assertTrue(result.isSuccess)
        assertTrue(tokenBlocklistDataSource.isRevoked(token))
    }

    @Test
    fun `logoutUser rejects invalid token`() = runTest {
        val result = repository.logoutUser("not-a-valid-jwt")

        assertTrue(result.isFailure)
    }

    @Test
    fun `validateToken rejects revoked token`() = runTest {
        val token = jwtUtil.generateToken(existingUser)

        assertTrue(repository.validateToken(token).isSuccess)

        repository.logoutUser(token)

        val revokedResult = repository.validateToken(token)
        assertTrue(revokedResult.isFailure)
        assertEquals("token_revoked", revokedResult.exceptionOrNull()?.message)
    }

    @Test
    fun `refreshToken reuse revokes every session of the owner`() = runTest {
        every { refreshTokenDataSource.validate("rotated") } returns null
        every { refreshTokenDataSource.ownerOfRevoked("rotated") } returns "user-123"

        val result = repository.refreshToken("rotated")

        assertTrue(result.isFailure)
        verify { refreshTokenDataSource.revokeAllForUser("user-123") }
    }

    @Test
    fun `logoutUser also revokes the refresh token of the same user`() = runTest {
        val token = jwtUtil.generateToken(existingUser)

        repository.logoutUser(token, refreshToken = "refresh-1")

        verify { refreshTokenDataSource.revokeIfOwnedBy("refresh-1", "user-123") }
    }
}
