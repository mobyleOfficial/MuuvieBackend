package org.mobyle.data.remote.auth.social

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.mobyle.domain.repository.AppleTokenRevoker
import org.slf4j.LoggerFactory
import java.security.KeyFactory
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

private val log = LoggerFactory.getLogger("AppleTokenRevoker")

/** The Sign in with Apple key of the team (Certificates, Identifiers & Profiles › Keys). */
data class AppleKeyConfig(
    val teamId: String,
    val keyId: String,
    /** The contents of the .p8 file (PEM, PKCS#8), with or without the BEGIN/END lines. */
    val privateKeyPem: String,
    /** The bundle ids the app signs in with; the code is exchanged with the one that issued it. */
    val clientIds: List<String>
)

/**
 * Exchanges an authorization code for the refresh token and revokes it, as Apple requires when an
 * account is deleted (https://developer.apple.com/documentation/sign_in_with_apple/revoke_tokens).
 * The client secret is an ES256 JWT signed with the team's key.
 */
class AppleTokenRevokerImpl(
    private val httpClient: HttpClient,
    private val config: AppleKeyConfig?,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 }
) : AppleTokenRevoker {

    private val privateKey: ECPrivateKey? by lazy { config?.let { parsePrivateKey(it.privateKeyPem) } }

    override val configured: Boolean get() = config != null && config.clientIds.isNotEmpty()

    override suspend fun revoke(authorizationCode: String): Boolean {
        val config = config ?: return false
        val key = privateKey ?: return false
        // The code came from one of the flavors; Apple answers invalid_client for the others.
        for (clientId in config.clientIds) {
            val secret = clientSecret(config, key, clientId)
            val tokenResponse = httpClient.submitForm(
                url = TOKEN_URL,
                formParameters = Parameters.build {
                    append("client_id", clientId)
                    append("client_secret", secret)
                    append("code", authorizationCode)
                    append("grant_type", "authorization_code")
                }
            )
            val body = tokenResponse.bodyAsText()
            if (!tokenResponse.status.isSuccess()) {
                log.info("Apple token exchange with $clientId answered ${tokenResponse.status.value}: ${body.take(200)}")
                continue
            }
            val tokens = Json.parseToJsonElement(body).jsonObject
            val (token, hint) = tokens["refresh_token"]?.jsonPrimitive?.contentOrNull?.let { it to "refresh_token" }
                ?: tokens["access_token"]?.jsonPrimitive?.contentOrNull?.let { it to "access_token" }
                ?: return false
            val revokeResponse = httpClient.submitForm(
                url = REVOKE_URL,
                formParameters = Parameters.build {
                    append("client_id", clientId)
                    append("client_secret", secret)
                    append("token", token)
                    append("token_type_hint", hint)
                }
            )
            if (revokeResponse.status.isSuccess()) return true
            log.warn("Apple revoke answered ${revokeResponse.status.value}: ${revokeResponse.bodyAsText().take(200)}")
            return false
        }
        return false
    }

    /** `{"alg":"ES256","kid":…}.{"iss":team,"iat":…,"exp":…,"aud":"https://appleid.apple.com","sub":clientId}`. */
    internal fun clientSecret(config: AppleKeyConfig, key: ECPrivateKey, clientId: String): String {
        val now = nowSeconds()
        val header = """{"alg":"ES256","kid":"${config.keyId}"}"""
        val claims = """{"iss":"${config.teamId}","iat":$now,"exp":${now + 300},"aud":"$APPLE_AUDIENCE","sub":"$clientId"}"""
        val signingInput = "${base64Url(header.toByteArray())}.${base64Url(claims.toByteArray())}"
        // P1363 gives the raw r||s a JWT carries, not DER.
        val signature = Signature.getInstance("SHA256withECDSAinP1363Format").run {
            initSign(key)
            update(signingInput.toByteArray())
            sign()
        }
        return "$signingInput.${base64Url(signature)}"
    }

    companion object {
        const val TOKEN_URL = "https://appleid.apple.com/auth/token"
        const val REVOKE_URL = "https://appleid.apple.com/auth/revoke"
        const val APPLE_AUDIENCE = "https://appleid.apple.com"

        fun parsePrivateKey(pem: String): ECPrivateKey {
            val base64 = pem.replace("\\n", "\n")
                .lines()
                .filterNot { it.startsWith("-----") }
                .joinToString("") { it.trim() }
            val spec = PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64))
            return KeyFactory.getInstance("EC").generatePrivate(spec) as ECPrivateKey
        }

        private fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
