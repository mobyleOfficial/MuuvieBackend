package org.mobyle.routing

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import org.mobyle.di.injection
import org.mobyle.domain.model.AuthToken
import org.mobyle.domain.model.LoginAuthTokenResponse
import org.mobyle.domain.model.LoginRequest
import org.mobyle.domain.model.OAuthCallbackRequest
import org.mobyle.domain.model.User
import org.mobyle.domain.model.UserProfile
import org.mobyle.domain.usecase.auth.LoginUser
import org.mobyle.domain.usecase.auth.LogoutUser
import org.mobyle.domain.usecase.auth.ProcessOAuthCallback
import org.mobyle.domain.usecase.auth.RefreshToken
import org.mobyle.domain.usecase.auth.SignUpUser
import org.mobyle.domain.usecase.auth.CheckNicknameAvailability
import org.mobyle.domain.model.SignUpRequest
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("AuthRouting")

fun Route.getAuthRouting() {
    val processOAuthCallback by injection<ProcessOAuthCallback>()
    val refreshTokenUseCase by injection<RefreshToken>()
    val loginUser by injection<LoginUser>()
    val signUpUser by injection<SignUpUser>()
    val logoutUser by injection<LogoutUser>()
    val checkNicknameAvailability by injection<CheckNicknameAvailability>()

    post("/auth/oauth/callback") {
        try {
            val request = call.receive<OAuthCallbackRequest>()

            if (request.code.isBlank() || request.state.isBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("invalid_request", "Missing required parameters: code, state")
                )
                return@post
            }

            val result = processOAuthCallback(request)

            if (result.isSuccess) {
                val authToken = result.getOrThrow()
                call.respond(
                    HttpStatusCode.OK,
                    AuthTokenResponse(
                        accessToken = authToken.accessToken,
                        tokenType = authToken.tokenType,
                        expiresIn = authToken.expiresIn,
                        refreshToken = authToken.refreshToken,
                        user = authToken.user
                    )
                )
                log.info("OAuth callback processed successfully for user ${authToken.user.id}")
            } else {
                val error = result.exceptionOrNull()?.message ?: "Unknown error"
                val (statusCode, errorCode) = when {
                    error.contains("invalid_state") -> HttpStatusCode.BadRequest to "invalid_state"
                    error.contains("invalid_code") -> HttpStatusCode.BadRequest to "invalid_code"
                    error.contains("provider_error") -> HttpStatusCode.BadGateway to "provider_error"
                    error.contains("user_creation_failed") -> HttpStatusCode.InternalServerError to "user_creation_failed"
                    else -> HttpStatusCode.InternalServerError to "internal_error"
                }

                log.error("OAuth callback failed: $error")
                call.respond(
                    statusCode,
                    ErrorResponse(errorCode, error)
                )
            }
        } catch (e: Exception) {
            log.error("OAuth callback request parsing error: ${e.message}")
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("invalid_request", "Invalid request body: ${e.message}")
            )
        }
    }

    post("/auth/login") {
        try {
            val request = call.receive<LoginRequest>()

            if (request.email.isBlank() || request.password.isBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("invalid_request", "Email and password are required")
                )
                return@post
            }

            val result = loginUser(request.email, request.password)

            if (result.isSuccess) {
                val authToken = result.getOrThrow()
                val user = authToken.user

                val profile = UserProfile(
                    id = user.id,
                    photoUrl = user.avatar ?: "",
                    username = user.username,
                    bio = user.bio ?: ""
                )

                val response = LoginAuthTokenResponse(
                    accessToken = authToken.accessToken,
                    tokenType = authToken.tokenType,
                    expiresIn = authToken.expiresIn,
                    refreshToken = authToken.refreshToken,
                    profile = profile
                )

                val statusCode = if (authToken.isNewUser) HttpStatusCode.Created else HttpStatusCode.OK
                call.respond(statusCode, response)
                log.info("Login successful for user ${user.id} (new=${authToken.isNewUser})")
            } else {
                val error = result.exceptionOrNull()?.message ?: "Unknown error"
                val (statusCode, errorCode, message) = when (error) {
                    "invalid_password_length" -> Triple(
                        HttpStatusCode.BadRequest,
                        "invalid_password_length",
                        "Password must be between 8 and 72 characters"
                    )
                    "invalid_credentials" -> Triple(
                        HttpStatusCode.Unauthorized,
                        "invalid_credentials",
                        "Invalid email or password"
                    )
                    "invalid_request" -> Triple(
                        HttpStatusCode.BadRequest,
                        "invalid_request",
                        "Invalid request"
                    )
                    else -> Triple(
                        HttpStatusCode.InternalServerError,
                        "internal_error",
                        "An internal error occurred"
                    )
                }

                log.error("Login failed: $error")
                call.respond(statusCode, ErrorResponse(errorCode, message))
            }
        } catch (e: Exception) {
            log.error("Login request parsing error: ${e.message}")
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("invalid_request", "Invalid request body: ${e.message}")
            )
        }
    }

    post("/auth/signup") {
        try {
            val request = call.receive<SignUpRequest>()

            if (request.email.isBlank() || request.password.isBlank() || request.nickname.isBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("invalid_request", "Email, password, and nickname are required")
                )
                return@post
            }

            val result = signUpUser(request.email, request.password, request.nickname)

            if (result.isSuccess) {
                val authToken = result.getOrThrow()
                val user = authToken.user

                val profile = UserProfile(
                    id = user.id,
                    photoUrl = user.avatar ?: "",
                    username = user.username,
                    bio = user.bio ?: ""
                )

                val response = LoginAuthTokenResponse(
                    accessToken = authToken.accessToken,
                    tokenType = authToken.tokenType,
                    expiresIn = authToken.expiresIn,
                    refreshToken = authToken.refreshToken,
                    profile = profile
                )

                call.respond(HttpStatusCode.Created, response)
                log.info("Sign up successful for user ${user.id}")
            } else {
                val error = result.exceptionOrNull()?.message ?: "Unknown error"
                val (statusCode, errorCode, message) = when (error) {
                    "invalid_password_length" -> Triple(
                        HttpStatusCode.BadRequest,
                        "invalid_password_length",
                        "Password must be between 8 and 72 characters"
                    )
                    "invalid_nickname" -> Triple(
                        HttpStatusCode.BadRequest,
                        "invalid_nickname",
                        "Nickname must be between 1 and 30 characters"
                    )
                    "email_already_exists" -> Triple(
                        HttpStatusCode.Conflict,
                        "email_already_exists",
                        "An account with this email already exists"
                    )
                    "nickname_already_exists" -> Triple(
                        HttpStatusCode.Conflict,
                        "nickname_already_exists",
                        "This nickname is already taken"
                    )
                    else -> Triple(
                        HttpStatusCode.InternalServerError,
                        error,
                        error
                    )
                }

                log.error("Sign up failed: $error")
                call.respond(statusCode, ErrorResponse(errorCode, message))
            }
        } catch (e: Exception) {
            log.error("Sign up request parsing error: ${e.message}")
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse("exception", "${e.javaClass.simpleName}: ${e.message}")
            )
        }
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
            val result = logoutUser(token)

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
                ErrorResponse("invalid_request", "Invalid request body: ${e.message}")
            )
        }
    }
}

@Serializable
data class AuthTokenResponse(
    val accessToken: String,
    val tokenType: String,
    val expiresIn: Long,
    val refreshToken: String? = null,
    val user: User
)

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
