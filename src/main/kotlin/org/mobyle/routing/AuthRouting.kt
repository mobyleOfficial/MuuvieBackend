package org.mobyle.routing

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.serialization.Serializable
import org.mobyle.data.remote.auth.authenticateJWT
import org.mobyle.di.injection
import org.mobyle.domain.model.LoginAuthTokenResponse
import org.mobyle.domain.model.NicknameRequest
import org.mobyle.domain.model.NicknameResponse
import org.mobyle.domain.model.SocialSignInRequest
import org.mobyle.domain.model.UserProfile
import org.mobyle.domain.usecase.auth.CheckNicknameAvailability
import org.mobyle.domain.usecase.auth.ChooseNickname
import org.mobyle.domain.usecase.auth.LogoutUser
import org.mobyle.domain.usecase.auth.RefreshToken
import org.mobyle.domain.usecase.auth.SignInWithProvider
import org.mobyle.domain.usecase.auth.ValidateToken
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("AuthRouting")

fun Route.getAuthRouting() {
    val signInWithProvider by injection<SignInWithProvider>()
    val chooseNickname by injection<ChooseNickname>()
    val validateToken by injection<ValidateToken>()
    val refreshTokenUseCase by injection<RefreshToken>()
    val logoutUser by injection<LogoutUser>()
    val checkNicknameAvailability by injection<CheckNicknameAvailability>()

    // Sign in with Apple or Google: the app sends the ID token of the native SDK. 201 when the account
    // is new; needsNickname stays true until the account picks a nickname (PUT /auth/nickname).
    post("/auth/social") {
        val request = call.receive<SocialSignInRequest>()
        val authToken = signInWithProvider(request.provider, request.idToken, request.nonce)
        val user = authToken.user
        val response = LoginAuthTokenResponse(
            accessToken = authToken.accessToken,
            tokenType = authToken.tokenType,
            expiresIn = authToken.expiresIn,
            refreshToken = authToken.refreshToken,
            profile = UserProfile(
                id = user.id,
                photoUrl = user.avatar ?: "",
                username = user.username,
                bio = user.bio ?: ""
            ),
            needsNickname = !user.nicknameConfirmed
        )
        call.respond(if (authToken.isNewUser) HttpStatusCode.Created else HttpStatusCode.OK, response)
    }

    put("/auth/nickname") {
        val principal = call.authenticateJWT(validateToken) ?: return@put
        val request = call.receive<NicknameRequest>()
        call.respond(NicknameResponse(chooseNickname(principal.claims.userId, request.nickname)))
    }

    get("/auth/check-nickname") {
        try {
            val nickname = call.request.queryParameters["nickname"]

            if (nickname.isNullOrBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("invalid_request", "Query parameter 'nickname' is required")
                )
                return@get
            }

            val result = checkNicknameAvailability(nickname)

            if (result.isSuccess) {
                val available = result.getOrThrow()
                call.respond(
                    HttpStatusCode.OK,
                    NicknameAvailabilityResponse(nickname = nickname.trim(), available = available)
                )
            } else {
                log.error("Nickname check failed: ${result.exceptionOrNull()?.message}")
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ErrorResponse("internal_error", "An internal error occurred")
                )
            }
        } catch (e: Exception) {
            log.error("Nickname check request error: ${e.message}")
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse("internal_error", "An internal error occurred")
            )
        }
    }

    post("/auth/logout") {
        try {
            val authHeader = call.request.headers["Authorization"]
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                call.respond(
                    HttpStatusCode.Unauthorized,
                    ErrorResponse("missing_token", "Authorization header with Bearer token is required")
                )
                return@post
            }

            val token = authHeader.substring("Bearer ".length)
            // Optional body { "refreshToken": "..." } also ends the refresh session.
            val refreshToken = runCatching { call.receive<RefreshTokenRequest>() }.getOrNull()?.refreshToken
            val result = logoutUser(token, refreshToken?.takeIf { it.isNotBlank() })

            if (result.isSuccess) {
                call.respond(HttpStatusCode.NoContent)
                log.info("Logout successful")
            } else {
                val error = result.exceptionOrNull()?.message ?: "Unknown error"
                log.error("Logout failed: $error")
                call.respond(
                    HttpStatusCode.Unauthorized,
                    ErrorResponse("invalid_token", "Token is invalid or already revoked")
                )
            }
        } catch (e: Exception) {
            log.error("Logout request error: ${e.message}")
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse("internal_error", "An internal error occurred")
            )
        }
    }

    post("/auth/refresh") {
        try {
            val request = call.receive<RefreshTokenRequest>()

            if (request.refreshToken.isBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("invalid_request", "Refresh token is required")
                )
                return@post
            }

            val result = refreshTokenUseCase(request.refreshToken)

            if (result.isSuccess) {
                val authToken = result.getOrThrow()
                call.respond(
                    HttpStatusCode.OK,
                    RefreshTokenResponse(
                        accessToken = authToken.accessToken,
                        tokenType = authToken.tokenType,
                        expiresIn = authToken.expiresIn,
                        refreshToken = authToken.refreshToken
                    )
                )
                log.info("Token refreshed successfully for user ${authToken.user.id}")
            } else {
                log.error("Token refresh failed: ${result.exceptionOrNull()?.message}")
                call.respond(
                    HttpStatusCode.Unauthorized,
                    ErrorResponse("invalid_refresh_token", "Refresh token expired or invalid")
                )
            }
        } catch (e: Exception) {
            log.error("Token refresh request parsing error: ${e.message}")
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("invalid_request", "Invalid request body")
            )
        }
    }
}

@Serializable
data class RefreshTokenResponse(
    val accessToken: String,
    val tokenType: String,
    val expiresIn: Long,
    val refreshToken: String? = null
)

@Serializable
data class RefreshTokenRequest(
    val refreshToken: String
)

@Serializable
data class NicknameAvailabilityResponse(
    val nickname: String,
    val available: Boolean
)

@Serializable
data class ErrorResponse(
    val error: String,
    val message: String
)
