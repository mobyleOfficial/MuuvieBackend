package org.mobyle.data.repository

import at.favre.lib.crypto.bcrypt.BCrypt
import org.mobyle.data.local.auth.RefreshTokenDataSource
import org.mobyle.data.local.auth.TokenBlocklistDataSource
import org.mobyle.data.local.oauth.OAuthStateDataSource
import org.mobyle.data.local.user.UserDatabaseDataSource
import org.mobyle.data.local.user.UserLocalDataSource
import org.mobyle.data.remote.oauth.OAuthDataSource
import org.mobyle.data.remote.auth.JWTUtil
import org.mobyle.domain.model.AuthToken
import org.mobyle.domain.model.JWTClaims
import org.mobyle.domain.model.OAuthCallbackRequest
import org.mobyle.domain.model.User
import org.mobyle.domain.repository.AuthRepository
import org.mobyle.domain.repository.UserRepository
import org.slf4j.LoggerFactory
import java.util.UUID

private val log = LoggerFactory.getLogger("AuthRepositoryImpl")

/** BCrypt work factor — increase to slow hashing (tradeoff: security vs. latency). */
private const val BCRYPT_COST = 12

/** Pre-computed dummy hash for timing-safe user-not-found path. */
private val DUMMY_HASH: String = BCrypt.withDefaults().hashToString(BCRYPT_COST, "dummy-password".toCharArray())

class AuthRepositoryImpl(
    private val oauthDataSource: OAuthDataSource?,
    private val oauthStateDataSource: OAuthStateDataSource,
    private val userRepository: UserRepository,
    private val jwtUtil: JWTUtil,
    private val userDatabaseDataSource: UserDatabaseDataSource,
    private val tokenBlocklistDataSource: TokenBlocklistDataSource,
    private val userLocalDataSource: UserLocalDataSource,
    private val refreshTokenDataSource: RefreshTokenDataSource
) : AuthRepository {

    override suspend fun processOAuthCallback(request: OAuthCallbackRequest): Result<AuthToken> {
        return try {
            if (oauthDataSource == null) {
                log.error("OAuth is not configured — missing OAUTH_CLIENT_ID / OAUTH_CLIENT_SECRET / OAUTH_PROVIDER_URL")
                return Result.failure(Exception("oauth_not_configured"))
            }

            // Validate state parameter (CSRF protection)
            val stateValid = oauthStateDataSource.validateAndRemoveState(request.state).getOrNull() ?: false
            if (!stateValid) {
                log.warn("Invalid OAuth state parameter")
                return Result.failure(Exception("invalid_state"))
            }

            // Exchange code for token
            val tokenResult = oauthDataSource.exchangeCodeForToken(request.code)
            if (tokenResult.isFailure) {
                log.error("OAuth token exchange failed: ${tokenResult.exceptionOrNull()?.message}")
                return Result.failure(Exception("invalid_code"))
            }

            val oauthToken = tokenResult.getOrThrow()

            // Get user info from OAuth provider
            val userInfoResult = oauthDataSource.getUserInfo(oauthToken.accessToken)
            if (userInfoResult.isFailure) {
                log.error("Failed to get OAuth user info: ${userInfoResult.exceptionOrNull()?.message}")
                return Result.failure(Exception("provider_error"))
            }

            var user = userInfoResult.getOrThrow()

            // Create or update user in local storage
            val savedUserResult = userRepository.createOrUpdateUser(user)
            if (savedUserResult.isFailure) {
                log.error("Failed to save user: ${savedUserResult.exceptionOrNull()?.message}")
                return Result.failure(Exception("user_creation_failed"))
            }

            user = savedUserResult.getOrThrow()

            // Generate JWT token
            val accessToken = jwtUtil.generateToken(user)

            val authToken = AuthToken(
                accessToken = accessToken,
                tokenType = "Bearer",
                expiresIn = jwtUtil.jwtExpirySeconds,
                refreshToken = oauthToken.refreshToken,
                user = user
            )

            Result.success(authToken)
        } catch (e: Exception) {
            log.error("OAuth callback processing failed: ${e.message}")
            Result.failure(Exception("internal_error"))
        }
    }

    override suspend fun validateToken(token: String): Result<JWTClaims> {
        // Check blocklist before validating signature
        if (tokenBlocklistDataSource.isRevoked(token)) {
            return Result.failure(Exception("token_revoked"))
        }
        return jwtUtil.validateToken(token)
    }

    override suspend fun refreshToken(refreshToken: String): Result<AuthToken> {
        return try {
            val userExternalId = refreshTokenDataSource.validate(refreshToken)
                ?: return Result.failure(Exception("invalid_refresh_token"))

            // Rotate: revoke old token before issuing new ones
            refreshTokenDataSource.revoke(refreshToken)

            val user = userDatabaseDataSource.findByExternalId(userExternalId)
                ?: return Result.failure(Exception("user_not_found"))

            val accessToken = jwtUtil.generateToken(user)
            val newRefreshToken = refreshTokenDataSource.create(userExternalId)

            Result.success(
                AuthToken(
                    accessToken = accessToken,
                    tokenType = "Bearer",
                    expiresIn = jwtUtil.jwtExpirySeconds,
                    refreshToken = newRefreshToken,
                    user = user.copy(passwordHash = null)
                )
            )
        } catch (e: Exception) {
            log.error("Token refresh failed: ${e.message}")
            Result.failure(Exception("internal_error"))
        }
    }

    override suspend fun getUserById(userId: String): Result<User> {
        val userResult = userRepository.getUserById(userId)
        return if (userResult.isSuccess && userResult.getOrNull() != null) {
            Result.success(userResult.getOrNull()!!)
        } else {
            Result.failure(Exception("user_not_found"))
        }
    }

    override suspend fun loginUser(email: String, password: String): Result<AuthToken> {
        return try {
            val normalizedEmail = email.trim().lowercase()
            val trimmedPassword = password.trim()

            // Validate password length (8-72, BCrypt limit)
            if (trimmedPassword.length < 8 || trimmedPassword.length > 72) {
                return Result.failure(Exception("invalid_password_length"))
            }

            if (normalizedEmail.isBlank()) {
                return Result.failure(Exception("invalid_request"))
            }

            val existingUser = userDatabaseDataSource.findByEmail(normalizedEmail)

            if (existingUser == null) {
                // Timing attack prevention: run BCrypt verify against dummy hash
                BCrypt.verifyer().verify(trimmedPassword.toCharArray(), DUMMY_HASH)
                return Result.failure(Exception("invalid_credentials"))
            }

            // Existing user — verify password
            val storedHash = existingUser.passwordHash
            if (storedHash == null) {
                // OAuth-only user trying to login with password — treat as wrong password
                BCrypt.verifyer().verify(trimmedPassword.toCharArray(), DUMMY_HASH)
                return Result.failure(Exception("invalid_credentials"))
            }

            val verified = BCrypt.verifyer().verify(trimmedPassword.toCharArray(), storedHash)
            if (!verified.verified) {
                return Result.failure(Exception("invalid_credentials"))
            }

            // Password correct — generate JWT and refresh token
            val accessToken = jwtUtil.generateToken(existingUser)
            val newRefreshToken = refreshTokenDataSource.create(existingUser.id)

            val authToken = AuthToken(
                accessToken = accessToken,
                tokenType = "Bearer",
                expiresIn = jwtUtil.jwtExpirySeconds,
                refreshToken = newRefreshToken,
                user = existingUser.copy(passwordHash = null),
                isNewUser = false
            )

            // Update L1 cache
            userLocalDataSource.saveUser(existingUser.copy(passwordHash = null))

            Result.success(authToken)
        } catch (e: Exception) {
            log.error("Login failed: ${e.message}")
            Result.failure(Exception("internal_error"))
        }
    }

    override suspend fun signUpUser(email: String, password: String, nickname: String): Result<AuthToken> {
        return try {
            println("[SIGNUP] Starting sign up for email=$email, nickname=$nickname")

            val normalizedEmail = email.trim().lowercase()
            val trimmedPassword = password.trim()
            val trimmedNickname = nickname.trim()

            if (normalizedEmail.isBlank()) {
                println("[SIGNUP] Invalid request: blank email")
                return Result.failure(Exception("invalid_request"))
            }

            if (trimmedPassword.length < 8 || trimmedPassword.length > 72) {
                println("[SIGNUP] Invalid password length: ${trimmedPassword.length}")
                return Result.failure(Exception("invalid_password_length"))
            }

            if (trimmedNickname.isBlank() || trimmedNickname.length > 30) {
                println("[SIGNUP] Invalid nickname: blank or too long (${trimmedNickname.length})")
                return Result.failure(Exception("invalid_nickname"))
            }

            // Check if email is already taken
            println("[SIGNUP] Checking email availability...")
            val existingUser = userDatabaseDataSource.findByEmail(normalizedEmail)
            if (existingUser != null) {
                println("[SIGNUP] Email already exists")
                return Result.failure(Exception("email_already_exists"))
            }
            println("[SIGNUP] Email available")

            // Check if nickname is already taken
            println("[SIGNUP] Checking nickname availability...")
            val existingUsernames = userDatabaseDataSource.findByUsername(trimmedNickname)
            if (existingUsernames.contains(trimmedNickname)) {
                println("[SIGNUP] Nickname already exists")
                return Result.failure(Exception("nickname_already_exists"))
            }
            println("[SIGNUP] Nickname available")

            println("[SIGNUP] Hashing password...")
            val passwordHash = BCrypt.withDefaults().hashToString(BCRYPT_COST, trimmedPassword.toCharArray())
            val userId = UUID.randomUUID().toString()
            val now = java.time.Instant.now().toString()

            val newUser = User(
                id = userId,
                email = normalizedEmail,
                username = trimmedNickname,
                avatar = null,
                createdAt = now,
                passwordHash = passwordHash
            )

            println("[SIGNUP] Saving user to database...")
            userDatabaseDataSource.save(newUser)
            println("[SIGNUP] User saved to database")

            userLocalDataSource.saveUser(newUser.copy(passwordHash = null))
            println("[SIGNUP] User saved to L1 cache")

            println("[SIGNUP] Generating JWT and refresh token...")
            val accessToken = jwtUtil.generateToken(newUser)
            val newRefreshToken = refreshTokenDataSource.create(userId)
            println("[SIGNUP] JWT and refresh token generated")

            val authToken = AuthToken(
                accessToken = accessToken,
                tokenType = "Bearer",
                expiresIn = jwtUtil.jwtExpirySeconds,
                refreshToken = newRefreshToken,
                user = newUser.copy(passwordHash = null),
                isNewUser = true
            )

            println("[SIGNUP] Sign up successful for userId=$userId")
            Result.success(authToken)
        } catch (e: Exception) {
            println("[SIGNUP] ERROR: ${e.javaClass.simpleName}: ${e.message}")
            e.printStackTrace()
            Result.failure(Exception("internal_error: ${e.javaClass.simpleName}: ${e.message}"))
        }
    }

    override suspend fun checkNicknameAvailability(nickname: String): Result<Boolean> {
        return try {
            val trimmed = nickname.trim()

            if (trimmed.isBlank() || trimmed.length > 30) {
                return Result.success(false)
            }

            val existing = userDatabaseDataSource.findByUsername(trimmed)
            Result.success(!existing.contains(trimmed))
        } catch (e: Exception) {
            log.error("Nickname availability check failed: ${e.message}")
            Result.failure(Exception("internal_error"))
        }
    }

    override suspend fun logoutUser(token: String): Result<Unit> {
        return try {
            val claimsResult = jwtUtil.validateToken(token)
            if (claimsResult.isFailure) {
                return Result.failure(Exception("invalid_token"))
            }

            val claims = claimsResult.getOrThrow()
            tokenBlocklistDataSource.revoke(token, claims.exp)
            log.info("Token revoked for user ${claims.userId}")
            Result.success(Unit)
        } catch (e: Exception) {
            log.error("Logout failed: ${e.message}")
            Result.failure(Exception("internal_error"))
        }
    }

    private fun generateUsername(email: String): String {
        val adjectives = listOf(
            "epic", "cosmic", "rebel", "shadow", "golden",
            "neon", "velvet", "chrome", "lunar", "stellar",
            "dark", "silent", "iron", "wild", "lost"
        )

        val movieReferences = listOf(
            "jedi", "hobbit", "gatsby", "morpheus", "ripley",
            "maverick", "neo", "gandalf", "stark", "wick",
            "mcfly", "indy", "furiosa", "vito", "t800",
            "joker", "rocky", "rambo", "batman", "logan",
            "simba", "woody", "nemo", "shrek", "gollum",
            "dumbledore", "yoda", "spock", "alien", "predator"
        )

        val random = java.util.concurrent.ThreadLocalRandom.current()
        val maxAttempts = 50

        repeat(maxAttempts) {
            val adjective = adjectives[random.nextInt(adjectives.size)]
            val movie = movieReferences[random.nextInt(movieReferences.size)]
            val number = random.nextInt(10, 1000)
            val candidate = "${adjective}_${movie}_$number"

            val existing = userDatabaseDataSource.findByUsername(candidate)
            if (!existing.contains(candidate)) {
                return candidate
            }
        }

        // Fallback: UUID-based
        return "moovie_${java.util.UUID.randomUUID().toString().take(8)}"
    }
}
