package org.mobyle.data.remote.auth

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable
import org.mobyle.domain.model.JWTClaims
import org.mobyle.domain.usecase.auth.ValidateToken
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("JWTAuth")

class JWTPrincipal(val claims: JWTClaims) : java.security.Principal {
    override fun getName(): String = claims.userId
}

@Serializable
data class ErrorResponse(
    val error: String,
    val message: String
)

/** Returns the principal if a valid JWT is present; returns null WITHOUT responding if absent or invalid. */
suspend fun ApplicationCall.tryAuthenticateJWT(validateToken: ValidateToken): JWTPrincipal? {
    val authHeader = request.headers["Authorization"]?.takeIf { it.startsWith("Bearer ") } ?: return null
    val token = authHeader.substring("Bearer ".length)
    return validateToken(token).getOrNull()?.let { JWTPrincipal(it) }
}

suspend fun ApplicationCall.authenticateJWT(validateToken: ValidateToken): JWTPrincipal? {
    return try {
        val authHeader = request.headers["Authorization"] ?: run {
            log.debug("Missing Authorization header")
            respond(
                HttpStatusCode.Unauthorized,
                ErrorResponse("missing_token", "Authorization header missing")
            )
            return null
        }

        if (!authHeader.startsWith("Bearer ")) {
            log.debug("Invalid Authorization header format")
            respond(
                HttpStatusCode.Unauthorized,
                ErrorResponse("invalid_token_format", "Authorization must be Bearer token")
            )
            return null
        }

        val token = authHeader.substring("Bearer ".length)
        val result = validateToken(token)

        if (result.isFailure) {
            val error = result.exceptionOrNull()?.message ?: "Unknown error"
            log.debug("JWT validation failed: $error")
            respond(
                HttpStatusCode.Unauthorized,
                ErrorResponse("invalid_token", error)
            )
            return null
        }

        JWTPrincipal(result.getOrThrow())
    } catch (e: Exception) {
        log.error("JWT authentication error: ${e.message}")
        respond(
            HttpStatusCode.Unauthorized,
            ErrorResponse("authentication_error", "Invalid token")
        )
        null
    }
}
