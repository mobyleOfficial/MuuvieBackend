package org.mobyle.data.repository

import org.mobyle.data.local.auth.RefreshTokenDataSource
import org.mobyle.data.local.auth.TokenBlocklistDataSource
import org.mobyle.data.local.auth.UserIdentityDataSource
import org.mobyle.data.local.user.UserDatabaseDataSource
import org.mobyle.data.local.user.UserLocalDataSource
import org.mobyle.data.remote.auth.JWTUtil
import org.mobyle.data.remote.auth.social.SocialTokenVerifier
import org.mobyle.domain.model.AuthToken
import org.mobyle.domain.model.ConflictException
import org.mobyle.domain.model.JWTClaims
import org.mobyle.domain.model.NotFoundException
import org.mobyle.domain.model.SocialIdentity
import org.mobyle.domain.model.SocialProvider
import org.mobyle.domain.model.User
import org.mobyle.domain.model.ValidationException
import org.mobyle.domain.repository.AuthRepository
import org.mobyle.domain.repository.UserRepository
import org.slf4j.LoggerFactory
import java.util.UUID

private val log = LoggerFactory.getLogger("AuthRepositoryImpl")

/** Longest nickname an account may pick. */
const val NICKNAME_MAX_LENGTH = 30

class AuthRepositoryImpl(
    private val socialTokenVerifier: SocialTokenVerifier,
    private val userIdentityDataSource: UserIdentityDataSource,
    private val userRepository: UserRepository,
    private val jwtUtil: JWTUtil,
    private val userDatabaseDataSource: UserDatabaseDataSource,
    private val tokenBlocklistDataSource: TokenBlocklistDataSource,
    private val userLocalDataSource: UserLocalDataSource,
    private val refreshTokenDataSource: RefreshTokenDataSource
) : AuthRepository {

    override suspend fun signInWithProvider(provider: SocialProvider, idToken: String, nonce: String?): AuthToken {
        val identity = socialTokenVerifier.verify(provider, idToken, nonce)

        val linkedUser = userIdentityDataSource.findUserExternalId(identity)
            ?.let { userDatabaseDataSource.findByExternalId(it) }
        if (linkedUser != null) return issueTokens(linkedUser, isNewUser = false)

        // An account with the same verified e-mail (one made with a password before, or with the other
        // provider) gets this identity too, so the same person keeps one account.
        val sameEmailUser = identity.email
            ?.takeIf { identity.emailVerified }
            ?.let { userDatabaseDataSource.findByEmail(it) }
        if (sameEmailUser != null) {
            userIdentityDataSource.link(sameEmailUser.id, identity)
            log.info("Linked ${identity.provider.wire} identity to existing user ${sameEmailUser.id}")
            return issueTokens(sameEmailUser, isNewUser = false)
        }

        val newUser = createUser(identity)
        log.info("Created user ${newUser.id} from ${identity.provider.wire} sign-in")
        return issueTokens(newUser, isNewUser = true)
    }

    override suspend fun chooseNickname(userId: String, nickname: String): String {
        val trimmed = nickname.trim()
        if (!isValidNickname(trimmed)) {
            throw ValidationException("nickname", "Nickname must have 1 to $NICKNAME_MAX_LENGTH characters")
        }
        userDatabaseDataSource.findByExternalId(userId) ?: throw NotFoundException("User not found")
        if (userDatabaseDataSource.isUsernameTaken(trimmed, userId)) {
            throw ConflictException("nickname_already_exists", "This nickname is already taken")
        }
        userDatabaseDataSource.confirmNickname(userId, trimmed)
        userDatabaseDataSource.findByExternalId(userId)?.let { userLocalDataSource.saveUser(it.copy(passwordHash = null)) }
        return trimmed
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
            if (userExternalId == null) {
                // A rotated token used again means it leaked: revoke every session of its owner.
                refreshTokenDataSource.ownerOfRevoked(refreshToken)?.let { owner ->
                    log.warn("Refresh token reuse detected; revoking all refresh tokens of user $owner")
                    refreshTokenDataSource.revokeAllForUser(owner)
                }
                return Result.failure(Exception("invalid_refresh_token"))
            }

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

    override suspend fun checkNicknameAvailability(nickname: String): Result<Boolean> {
        return try {
            val trimmed = nickname.trim()

            if (!isValidNickname(trimmed)) {
                return Result.success(false)
            }

            val existing = userDatabaseDataSource.findByUsername(trimmed)
            Result.success(!existing.contains(trimmed))
        } catch (e: Exception) {
            log.error("Nickname availability check failed: ${e.message}")
            Result.failure(Exception("internal_error"))
        }
    }

    override suspend fun logoutUser(token: String, refreshToken: String?): Result<Unit> {
        return try {
            val claimsResult = jwtUtil.validateToken(token)
            if (claimsResult.isFailure) {
                return Result.failure(Exception("invalid_token"))
            }

            val claims = claimsResult.getOrThrow()
            tokenBlocklistDataSource.revoke(token, claims.exp)
            refreshToken?.let { refreshTokenDataSource.revokeIfOwnedBy(it, claims.userId) }
            log.info("Token revoked for user ${claims.userId}")
            Result.success(Unit)
        } catch (e: Exception) {
            log.error("Logout failed: ${e.message}")
            Result.failure(Exception("internal_error"))
        }
    }

    private suspend fun createUser(identity: SocialIdentity): User {
        val user = User(
            id = UUID.randomUUID().toString(),
            email = identity.email.orEmpty(),
            username = generateUsername(),
            avatar = identity.picture,
            createdAt = java.time.Instant.now().toString(),
            nicknameConfirmed = false
        )
        userDatabaseDataSource.save(user)
        userIdentityDataSource.link(user.id, identity)
        userLocalDataSource.saveUser(user)
        return user
    }

    private suspend fun issueTokens(user: User, isNewUser: Boolean): AuthToken {
        val publicUser = user.copy(passwordHash = null)
        userLocalDataSource.saveUser(publicUser)
        return AuthToken(
            accessToken = jwtUtil.generateToken(publicUser),
            tokenType = "Bearer",
            expiresIn = jwtUtil.jwtExpirySeconds,
            refreshToken = refreshTokenDataSource.create(user.id),
            user = publicUser,
            isNewUser = isNewUser
        )
    }

    /** A placeholder nickname like `cosmic_ripley_42`, until the account picks its own. */
    private fun generateUsername(): String {
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
        repeat(50) {
            val adjective = adjectives[random.nextInt(adjectives.size)]
            val movie = movieReferences[random.nextInt(movieReferences.size)]
            val number = random.nextInt(10, 1000)
            val candidate = "${adjective}_${movie}_$number"

            val existing = userDatabaseDataSource.findByUsername(candidate)
            if (!existing.contains(candidate)) {
                return candidate
            }
        }

        return "muuvie_${UUID.randomUUID().toString().take(8)}"
    }
}

/**
 * 1 to [NICKNAME_MAX_LENGTH] characters, no control characters, quotes or backslashes
 * (the nickname is written into the access token's JSON).
 */
internal fun isValidNickname(nickname: String): Boolean =
    nickname.isNotBlank() &&
        nickname.length <= NICKNAME_MAX_LENGTH &&
        nickname.none { it.isISOControl() || it == '"' || it == '\\' }
