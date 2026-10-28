package org.mobyle.data.remote.push

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** A notification for one device. [data] reaches the app so a tap opens the right screen. */
data class PushMessage(val token: String, val title: String, val body: String, val data: Map<String, String>)

interface PushSender {
    /** Sends each message; answers the tokens the provider said are no longer valid. */
    suspend fun send(messages: List<PushMessage>): Set<String>
}

/** Used while FCM is not configured: push is off and the WebSocket still delivers in real time. */
class DisabledPushSender : PushSender {
    override suspend fun send(messages: List<PushMessage>): Set<String> = emptySet()
}

/**
 * Firebase Cloud Messaging HTTP v1 (F6.7). Authenticates with a service account: a JWT signed
 * with its private key is exchanged for an OAuth token, kept until shortly before it expires.
 */
class FcmPushSender(private val httpClient: HttpClient, private val account: ServiceAccount) : PushSender {
    private val log = LoggerFactory.getLogger(FcmPushSender::class.java)
    private val tokenLock = Mutex()
    private var accessToken: String? = null
    private var expiresAt: Instant = Instant.DISTANT_PAST

    override suspend fun send(messages: List<PushMessage>): Set<String> {
        if (messages.isEmpty()) return emptySet()
        val token = accessToken()
        val invalid = mutableSetOf<String>()
        for (message in messages) {
            val response = httpClient.post("https://fcm.googleapis.com/v1/projects/${account.projectId}/messages:send") {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(payload(message).toString())
            }
            if (response.status.isSuccess()) continue
            val error = response.bodyAsText()
            // UNREGISTERED (404) or an invalid token (400): the app was removed or the token rotated.
            if (response.status.value == 404 || "UNREGISTERED" in error || "registration token" in error) {
                invalid += message.token
            } else {
                log.warn("[PUSH] FCM answered ${response.status.value}: $error")
            }
        }
        return invalid
    }

    private fun payload(message: PushMessage) = buildJsonObject {
        putJsonObject("message") {
            put("token", message.token)
            putJsonObject("notification") {
                put("title", message.title)
                put("body", message.body)
            }
            putJsonObject("data") { message.data.forEach { (key, value) -> put(key, value) } }
            putJsonObject("android") {
                put("priority", "high")
                putJsonObject("notification") { put("channel_id", ANDROID_CHANNEL) }
            }
        }
    }

    private suspend fun accessToken(): String = tokenLock.withLock {
        val now = Clock.System.now()
        accessToken?.takeIf { now < expiresAt }?.let { return@withLock it }
        val response = httpClient.submitForm(
            url = account.tokenUri,
            formParameters = parameters {
                append("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer")
                append("assertion", signedJwt(now))
            }
        ).body<TokenResponse>()
        accessToken = response.accessToken
        expiresAt = now + (response.expiresIn - 300).coerceAtLeast(60).seconds
        response.accessToken
    }

    private fun signedJwt(now: Instant): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("""{"alg":"RS256","typ":"JWT"}""".toByteArray())
        val claims = buildJsonObject {
            put("iss", account.clientEmail)
            put("scope", "https://www.googleapis.com/auth/firebase.messaging")
            put("aud", account.tokenUri)
            put("iat", now.epochSeconds)
            put("exp", (now + 60.minutes).epochSeconds)
        }
        val body = encoder.encodeToString(claims.toString().toByteArray())
        val signature = Signature.getInstance("SHA256withRSA").apply {
            initSign(account.privateKey())
            update("$header.$body".toByteArray())
        }.sign()
        return "$header.$body.${encoder.encodeToString(signature)}"
    }

    @Serializable
    private data class TokenResponse(
        @SerialName("access_token") val accessToken: String,
        @SerialName("expires_in") val expiresIn: Long
    )

    /** The fields of a Google service account JSON key that FCM needs. */
    @Serializable
    data class ServiceAccount(
        @SerialName("project_id") val projectId: String,
        @SerialName("client_email") val clientEmail: String,
        @SerialName("private_key") val privateKeyPem: String,
        @SerialName("token_uri") val tokenUri: String = "https://oauth2.googleapis.com/token"
    ) {
        fun privateKey() = KeyFactory.getInstance("RSA").generatePrivate(
            PKCS8EncodedKeySpec(
                Base64.getDecoder().decode(
                    privateKeyPem.replace("-----BEGIN PRIVATE KEY-----", "")
                        .replace("-----END PRIVATE KEY-----", "")
                        .replace("\\s".toRegex(), "")
                )
            )
        )

        companion object {
            private val json = Json { ignoreUnknownKeys = true }

            fun parse(text: String): ServiceAccount = json.decodeFromString(serializer(), text)
        }
    }

    companion object {
        // Must match the channel the app creates on Android.
        const val ANDROID_CHANNEL = "social"
    }
}
