package org.mobyle.data.repository

import at.favre.lib.crypto.bcrypt.BCrypt
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.mobyle.data.local.database.TokenBlocklistTable
import kotlin.test.BeforeTest
import org.mobyle.data.local.auth.RefreshTokenDataSource
import org.mobyle.data.local.auth.TokenBlocklistDataSource
import org.mobyle.data.local.oauth.OAuthStateDataSource
import org.mobyle.data.local.user.UserDatabaseDataSource
import org.mobyle.data.local.user.UserLocalDataSource
import org.mobyle.data.remote.auth.JWTUtil
import org.mobyle.domain.model.User
import org.mobyle.domain.repository.UserRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthRepositoryImplTest {

    private val oauthStateDataSource = mockk<OAuthStateDataSource>()
    private val userRepository = mockk<UserRepository>()
    private val userDatabaseDataSource = mockk<UserDatabaseDataSource>()
    private val tokenBlocklistDataSource = TokenBlocklistDataSource()
    private val userLocalDataSource = mockk<UserLocalDataSource>(relaxed = true)
    private val refreshTokenDataSource = mockk<RefreshTokenDataSource>(relaxed = true)
    private val jwtUtil = JWTUtil(
        jwtSecret = "test-secret-key-with-minimum-32-characters-long",
        jwtExpirySeconds = 3600
    )

    private val repository = AuthRepositoryImpl(
        oauthDataSource = null,
        oauthStateDataSource = oauthStateDataSource,
        userRepository = userRepository,
        jwtUtil = jwtUtil,
        userDatabaseDataSource = userDatabaseDataSource,
        tokenBlocklistDataSource = tokenBlocklistDataSource,
        userLocalDataSource = userLocalDataSource,
        refreshTokenDataSource = refreshTokenDataSource
    )

    @BeforeTest
    fun connectDatabase() {
        // TokenBlocklistDataSource is backed by a table.
        Database.connect("jdbc:h2:mem:auth_repository;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        transaction {
            SchemaUtils.drop(TokenBlocklistTable)
            SchemaUtils.create(TokenBlocklistTable)
        }
    }

    // Login no longer creates accounts; sign-up does.
    @Test
    fun `loginUser fails when email not found`() = runTest {
        every { userDatabaseDataSource.findByEmail("new@example.com") } returns null

        val result = repository.loginUser("new@example.com", "securePassword123")

        assertTrue(result.isFailure)
        assertEquals("invalid_credentials", result.exceptionOrNull()?.message)
    }

    @Test
    fun `loginUser returns success for existing user with correct password`() = runTest {
        val passwordHash = BCrypt.withDefaults().hashToString(12, "correctPassword1".toCharArray())
        val existingUser = User(
            id = "user-123",
            email = "existing@example.com",
            username = "existing",
            avatar = null,
            createdAt = "2026-07-22T10:00:00Z",
            passwordHash = passwordHash
        )

        every { userDatabaseDataSource.findByEmail("existing@example.com") } returns existingUser

        val result = repository.loginUser("existing@example.com", "correctPassword1")

        assertTrue(result.isSuccess)
        val authToken = result.getOrThrow()
        assertFalse(authToken.isNewUser)
        assertEquals("existing", authToken.user.username)
        // Password hash must not be in the returned user
        assertEquals(null, authToken.user.passwordHash)
    }

    @Test
    fun `loginUser returns failure for wrong password`() = runTest {
        val passwordHash = BCrypt.withDefaults().hashToString(12, "correctPassword1".toCharArray())
        val existingUser = User(
            id = "user-123",
            email = "existing@example.com",
            username = "existing",
            avatar = null,
            createdAt = "2026-07-22T10:00:00Z",
            passwordHash = passwordHash
        )

        every { userDatabaseDataSource.findByEmail("existing@example.com") } returns existingUser

        val result = repository.loginUser("existing@example.com", "wrongPassword1")

        assertTrue(result.isFailure)
        assertEquals("invalid_credentials", result.exceptionOrNull()?.message)
    }

    @Test
    fun `loginUser rejects password shorter than 8 chars`() = runTest {
        val result = repository.loginUser("test@example.com", "short")

        assertTrue(result.isFailure)
        assertEquals("invalid_password_length", result.exceptionOrNull()?.message)
    }

    @Test
    fun `loginUser rejects password longer than 72 chars`() = runTest {
        val longPassword = "a".repeat(73)
        val result = repository.loginUser("test@example.com", longPassword)

        assertTrue(result.isFailure)
        assertEquals("invalid_password_length", result.exceptionOrNull()?.message)
    }

    @Test
    fun `loginUser normalizes email to lowercase`() = runTest {
        val passwordHash = BCrypt.withDefaults().hashToString(4, "securePassword123".toCharArray())
        every { userDatabaseDataSource.findByEmail("test@example.com") } returns User(
            id = "user-1",
            email = "test@example.com",
            username = "test",
            avatar = null,
            createdAt = "2026-07-22T10:00:00Z",
            passwordHash = passwordHash
        )

        val result = repository.loginUser("TEST@EXAMPLE.COM", "securePassword123")

        assertTrue(result.isSuccess)
        assertEquals("test@example.com", result.getOrThrow().user.email)
    }

    @Test
    fun `logoutUser revokes valid token`() = runTest {
        val user = User(
            id = "user-123",
            email = "test@example.com",
            username = "testuser",
            avatar = null,
            createdAt = "2026-07-22T10:00:00Z"
        )
        val token = jwtUtil.generateToken(user)

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
        val user = User(
            id = "user-123",
            email = "test@example.com",
            username = "testuser",
            avatar = null,
            createdAt = "2026-07-22T10:00:00Z"
        )
        val token = jwtUtil.generateToken(user)

        // First validate should succeed
        val validResult = repository.validateToken(token)
        assertTrue(validResult.isSuccess)

        // Revoke the token
        repository.logoutUser(token)

        // Now validate should fail
        val revokedResult = repository.validateToken(token)
        assertTrue(revokedResult.isFailure)
        assertEquals("token_revoked", revokedResult.exceptionOrNull()?.message)
    }

    @Test
    fun `signUpUser rejects a nickname already in use`() = runTest {
        every { userDatabaseDataSource.findByEmail("joao@example.com") } returns null
        every { userDatabaseDataSource.findByUsername("joao") } returns listOf("joao")

        val result = repository.signUpUser("joao@example.com", "securePassword123", "joao")

        assertTrue(result.isFailure)
        assertEquals("nickname_already_exists", result.exceptionOrNull()?.message)
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
        val user = User(
            id = "user-123",
            email = "test@example.com",
            username = "testuser",
            avatar = null,
            createdAt = "2026-07-22T10:00:00Z"
        )
        val token = jwtUtil.generateToken(user)

        repository.logoutUser(token, refreshToken = "refresh-1")

        verify { refreshTokenDataSource.revokeIfOwnedBy("refresh-1", "user-123") }
    }
}
