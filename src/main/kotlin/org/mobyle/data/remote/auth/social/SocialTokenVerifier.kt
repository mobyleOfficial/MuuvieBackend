package org.mobyle.data.remote.auth.social

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.mobyle.domain.model.SocialIdentity
import org.mobyle.domain.model.SocialProvider
import org.mobyle.domain.model.UnauthorizedException
import org.mobyle.domain.model.UnavailableException
import org.slf4j.LoggerFactory
import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

private val log = LoggerFactory.getLogger("SocialTokenVerifier")

/** Checks an ID token from a native sign-in SDK and says who signed in. */
interface SocialTokenVerifier {
    /**
     * @throws UnavailableException `provider_not_configured` when the server has no client id for [provider].
     * @throws UnauthorizedException `invalid_token` when the signature, issuer, audience, expiry or nonce is wrong.
     */
    suspend fun verify(provider: SocialProvider, idToken: String, nonce: String?): SocialIdentity
}

/** The public keys a provider signs its ID tokens with, by key id. */
fun interface JwksSource {
    suspend fun key(kid: String): RSAPublicKey?
}

/** What a provider's ID tokens must carry to be accepted. */
data class ProviderConfig(
    val issuers: Set<String>,
    /** Client ids of the apps: Google's iOS, Android and web clients; Apple's bundle ids and Services ID. */
    val audiences: Set<String>,
    val jwks: JwksSource
)

object SocialProviders {
    const val GOOGLE_JWKS_URL = "https://www.googleapis.com/oauth2/v3/certs"
    const val APPLE_JWKS_URL = "https://appleid.apple.com/auth/keys"
    val GOOGLE_ISSUERS = setOf("https://accounts.google.com", "accounts.google.com")
    val APPLE_ISSUERS = setOf("https://appleid.apple.com")
}

/**
 * Verifies RS256 ID tokens against the provider's JWKS. A provider with no audiences configured is off.
 *
 * Nonce: the app generates a raw nonce, gives the SDK its SHA-256 (hex) and sends the raw one here.
 * Apple tokens must carry it; a Google token is checked only when it carries one.
 */
class IdTokenVerifier(
    private val configs: Map<SocialProvider, ProviderConfig>,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    private val clockSkewSeconds: Long = 60
) : SocialTokenVerifier {

    override suspend fun verify(provider: SocialProvider, idToken: String, nonce: String?): SocialIdentity {
        val config = configs[provider]?.takeIf { it.audiences.isNotEmpty() }
            ?: throw UnavailableException("provider_not_configured", "Sign in with ${provider.wire} is not configured")

        val parts = idToken.trim().split(".")
        if (parts.size != 3) invalid("malformed token")
        val header = parseJson(parts[0]) ?: invalid("malformed header")
        val claims = parseJson(parts[1]) ?: invalid("malformed payload")

        if (header.string("alg") != "RS256") invalid("unexpected alg")
        val kid = header.string("kid") ?: invalid("missing kid")
        val key = config.jwks.key(kid) ?: invalid("unknown kid")
        if (!verifySignature("${parts[0]}.${parts[1]}", decodeBase64Url(parts[2]), key)) invalid("bad signature")

        if (claims.string("iss") !in config.issuers) invalid("unexpected issuer")
        val audiences = when (val aud = claims["aud"]) {
            is JsonPrimitive -> setOfNotNull(aud.contentOrNull)
            is JsonArray -> aud.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()
            else -> emptySet()
        }
        if (audiences.none { it in config.audiences }) invalid("unexpected audience")

        val now = nowSeconds()
        val exp = claims.long("exp") ?: invalid("missing exp")
        if (exp + clockSkewSeconds < now) invalid("expired")
        val iat = claims.long("iat")
        if (iat != null && iat - clockSkewSeconds > now) invalid("issued in the future")

        val tokenNonce = claims.string("nonce")
        when {
            tokenNonce != null -> if (nonce.isNullOrBlank() || !nonceMatches(nonce, tokenNonce)) invalid("nonce mismatch")
            provider == SocialProvider.APPLE -> invalid("missing nonce")
        }

        val subject = claims.string("sub")?.takeIf { it.isNotBlank() } ?: invalid("missing sub")
        val email = claims.string("email")?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        val emailVerified = when (val verified = claims["email_verified"]) {
            is JsonPrimitive -> verified.booleanOrNull ?: (verified.contentOrNull == "true")
            else -> false
        }
        return SocialIdentity(
            provider = provider,
            subject = subject,
            email = email,
            emailVerified = email != null && emailVerified,
            picture = claims.string("picture")?.takeIf { it.length <= 500 }
        )
    }

    private fun invalid(reason: String): Nothing {
        log.info("Rejected ID token: $reason")
        throw UnauthorizedException("invalid_token", "The sign-in token is invalid or expired")
    }
}

/** The nonce in the token is the SHA-256 (hex) of the raw one; a token carrying the raw nonce is accepted too. */
internal fun nonceMatches(rawNonce: String, tokenNonce: String): Boolean {
    val hashed = sha256Hex(rawNonce)
    return MessageDigest.isEqual(hashed.toByteArray(), tokenNonce.toByteArray()) ||
        MessageDigest.isEqual(rawNonce.toByteArray(), tokenNonce.toByteArray())
}

internal fun sha256Hex(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

private fun verifySignature(signingInput: String, signature: ByteArray, key: RSAPublicKey): Boolean = try {
    Signature.getInstance("SHA256withRSA").run {
        initVerify(key)
        update(signingInput.toByteArray())
        verify(signature)
    }
} catch (e: Exception) {
    false
}

private fun parseJson(segment: String): JsonObject? = try {
    Json.parseToJsonElement(decodeBase64Url(segment).decodeToString()).jsonObject
} catch (e: Exception) {
    null
}

private fun decodeBase64Url(value: String): ByteArray = Base64.getUrlDecoder().decode(value.trimEnd('='))

private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.long(name: String): Long? = (this[name] as? JsonPrimitive)?.longOrNull

/** Reads `{"keys":[{"kty":"RSA","kid":…,"n":…,"e":…}]}` into RSA keys by key id. */
internal fun parseJwks(body: String): Map<String, RSAPublicKey> {
    val factory = KeyFactory.getInstance("RSA")
    return Json.parseToJsonElement(body).jsonObject["keys"]?.jsonArray.orEmpty().mapNotNull { element ->
        val jwk = element.jsonObject
        if (jwk.string("kty") != "RSA") return@mapNotNull null
        val kid = jwk.string("kid") ?: return@mapNotNull null
        val n = jwk.string("n") ?: return@mapNotNull null
        val e = jwk.string("e") ?: return@mapNotNull null
        val spec = RSAPublicKeySpec(BigInteger(1, decodeBase64Url(n)), BigInteger(1, decodeBase64Url(e)))
        kid to (factory.generatePublic(spec) as RSAPublicKey)
    }.toMap()
}

/**
 * Fetches a JWKS over HTTP and keeps it for [ttlMillis]. An unknown key id refetches at most once a
 * minute, so a key rotation is picked up without letting junk tokens hammer the provider.
 */
class HttpJwksSource(
    private val httpClient: HttpClient,
    private val url: String,
    private val ttlMillis: Long = 60 * 60 * 1000,
    private val nowMillis: () -> Long = System::currentTimeMillis
) : JwksSource {
    private val mutex = Mutex()
    private var keys: Map<String, RSAPublicKey> = emptyMap()
    private var fetchedAt = 0L

    override suspend fun key(kid: String): RSAPublicKey? = mutex.withLock {
        val age = nowMillis() - fetchedAt
        if (age > ttlMillis || (kid !in keys && age > 60_000)) {
            try {
                keys = parseJwks(httpClient.get(url).bodyAsText())
                fetchedAt = nowMillis()
            } catch (e: Exception) {
                log.warn("Could not fetch JWKS from $url: ${e.message}")
                if (keys.isEmpty()) {
                    throw UnavailableException("provider_unreachable", "The sign-in provider could not be reached")
                }
            }
        }
        keys[kid]
    }
}
