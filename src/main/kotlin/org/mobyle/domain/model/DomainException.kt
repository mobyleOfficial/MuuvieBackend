package org.mobyle.domain.model

import kotlinx.serialization.Serializable

/** Error envelope of the v1 contract (research/decisions/api-v1-contract.md). */
@Serializable
data class ApiError(val error: String, val message: String, val details: Map<String, String>? = null)

/**
 * Errors a use case raises on purpose. StatusPages maps each one to its status and to the
 * error envelope of the v1 contract; [code] is the stable `error` key the app reads.
 * The message is shown to clients, so it never carries internal details.
 */
sealed class DomainException(val code: String, message: String) : RuntimeException(message)

/** The resource does not exist, or the viewer is not allowed to know it exists. */
class NotFoundException(message: String = "Not found") : DomainException("not_found", message)

/** Forbidden action on a resource the viewer can see. */
class ForbiddenException(code: String, message: String) : DomainException(code, message)

class ValidationException(val field: String, message: String) : DomainException("validation_failed", message)

class ConflictException(code: String, message: String) : DomainException(code, message)

/** A dependency the feature needs is not configured or not reachable. */
class UnavailableException(code: String, message: String) : DomainException(code, message)

/** The user did an action too many times in a short window (B6 rate limits). */
class TooManyRequestsException(code: String, message: String) : DomainException(code, message)
