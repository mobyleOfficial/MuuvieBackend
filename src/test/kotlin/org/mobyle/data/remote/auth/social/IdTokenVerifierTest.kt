package org.mobyle.data.remote.auth.social

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.mobyle.domain.model.SocialProvider
import org.mobyle.domain.model.UnauthorizedException
import org.mobyle.domain.model.UnavailableException
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IdTokenVerifierTest {

    private val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val otherKeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val now = 1_800_000_000L

    private val jwks = JwksSource { kid -> if (kid == "k1") keyPair.public as RSAPublicKey else null }

    private val verifier = IdTokenVerifier(
        configs = mapOf(
            SocialProvider.GOOGLE to ProviderConfig(SocialProviders.GOOGLE_ISSUERS, setOf("google-client"), jwks),
            SocialProvider.APPLE to ProviderConfig(SocialProviders.APPLE_ISSUERS, setOf("org.mobyle.muuvie"), jwks)
        ),
        nowSeconds = { now }
    )

    private fun token(
        claims: Map<String, Any?>,
        kid: String = "k1",
        alg: String = "RS256",
        key: RSAPrivateKey = keyPair.private as RSAPrivateKey
    ): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("""{"alg":"$alg","kid":"$kid"}""".toByteArray())
        val payload = encoder.encodeToString(JsonObject(claims.mapValues { json(it.value) }).toString().toByteArray())
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(key)
            update("$header.$payload".toByteArray())
            sign()
        }
        return "$header.$payload.${encoder.encodeToString(signature)}"
    }

    private fun json(value: Any?): JsonElement = when (value) {
        is String -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is List<*> -> kotlinx.serialization.json.JsonArray(value.map { json(it) })
        else -> kotlinx.serialization.json.JsonNull
    }

    private fun google(vararg overrides: Pair<String, Any?>) = mapOf(
        "iss" to "https://accounts.google.com",
        "aud" to "google-client",
        "sub" to "1234",
        "email" to "Ana@Example.com",
        "email_verified" to true,
        "picture" to "https://example.com/a.png",
        "iat" to now - 10,
        "exp" to now + 3600
    ) + overrides

    private fun apple(vararg overrides: Pair<String, Any?>) = mapOf(
        "iss" to "https://appleid.apple.com",
        "aud" to "org.mobyle.muuvie",
        "sub" to "000123.abc",
        "email" to "x@privaterelay.appleid.com",
        "email_verified" to "true",
        "nonce" to sha256Hex("raw-nonce"),
        "iat" to now - 10,
        "exp" to now + 600
    ) + overrides

    @Test
    fun `a valid Google token gives its identity with the e-mail lowercased`() = runTest {
        val identity = verifier.verify(SocialProvider.GOOGLE, token(google()), null)

        assertEquals("1234", identity.subject)
        assertEquals("ana@example.com", identity.email)
        assertTrue(identity.emailVerified)
        assertEquals("https://example.com/a.png", identity.picture)
    }

    @Test
    fun `a valid Apple token with the hashed nonce is accepted, e-mail_verified as a string`() = runTest {
        val identity = verifier.verify(SocialProvider.APPLE, token(apple()), "raw-nonce")

        assertEquals("000123.abc", identity.subject)
        assertTrue(identity.emailVerified)
    }

    @Test
    fun `an audience list containing a client id is accepted`() = runTest {
        verifier.verify(SocialProvider.GOOGLE, token(google("aud" to listOf("other", "google-client"))), null)
    }

    @Test
    fun `the short Google issuer is accepted`() = runTest {
        verifier.verify(SocialProvider.GOOGLE, token(google("iss" to "accounts.google.com")), null)
    }

    @Test
    fun `an unverified e-mail is reported as not verified`() = runTest {
        val identity = verifier.verify(SocialProvider.GOOGLE, token(google("email_verified" to false)), null)
        assertFalse(identity.emailVerified)
    }

    @Test
    fun `a token without e-mail has no e-mail`() = runTest {
        val identity = verifier.verify(SocialProvider.APPLE, token(apple("email" to null, "email_verified" to null)), "raw-nonce")
        assertNull(identity.email)
        assertFalse(identity.emailVerified)
    }

    @Test
    fun `wrong tokens are rejected`() = runTest {
        val rejected = listOf(
            "signed by another key" to token(google(), key = otherKeyPair.private as RSAPrivateKey),
            "unknown kid" to token(google(), kid = "k2"),
            "alg none" to token(google(), alg = "none"),
            "other audience" to token(google("aud" to "someone-else")),
            "other issuer" to token(google("iss" to "https://evil.example.com")),
            "expired" to token(google("exp" to now - 3600)),
            "issued in the future" to token(google("iat" to now + 3600)),
            "no subject" to token(google("sub" to "")),
            "not a JWT" to "abc.def",
            "tampered payload" to token(google()).split(".").let { (h, _, s) -> "$h.${token(google("sub" to "evil")).split(".")[1]}.$s" }
        )
        rejected.forEach { (case, idToken) ->
            assertFailsWith<UnauthorizedException>(case) { verifier.verify(SocialProvider.GOOGLE, idToken, null) }
        }
    }

    @Test
    fun `an Apple token needs the nonce it was issued for`() = runTest {
        assertFailsWith<UnauthorizedException> { verifier.verify(SocialProvider.APPLE, token(apple()), null) }
        assertFailsWith<UnauthorizedException> { verifier.verify(SocialProvider.APPLE, token(apple()), "other-nonce") }
        assertFailsWith<UnauthorizedException> {
            verifier.verify(SocialProvider.APPLE, token(apple("nonce" to null)), "raw-nonce")
        }
    }

    @Test
    fun `a Google token carrying a nonce is checked against it`() = runTest {
        val idToken = token(google("nonce" to sha256Hex("n")))
        verifier.verify(SocialProvider.GOOGLE, idToken, "n")
        assertFailsWith<UnauthorizedException> { verifier.verify(SocialProvider.GOOGLE, idToken, "m") }
    }

    @Test
    fun `a provider without client ids is not configured`() = runTest {
        val off = IdTokenVerifier(
            mapOf(SocialProvider.GOOGLE to ProviderConfig(SocialProviders.GOOGLE_ISSUERS, emptySet(), jwks)),
            nowSeconds = { now }
        )
        val error = assertFailsWith<UnavailableException> { off.verify(SocialProvider.GOOGLE, token(google()), null) }
        assertEquals("provider_not_configured", error.code)
        assertFailsWith<UnavailableException> { off.verify(SocialProvider.APPLE, token(apple()), "raw-nonce") }
    }

    @Test
    fun `parseJwks reads RSA keys by kid and skips the rest`() {
        val public = keyPair.public as RSAPublicKey
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val n = encoder.encodeToString(public.modulus.toByteArray().dropWhile { it == 0.toByte() }.toByteArray())
        val e = encoder.encodeToString(public.publicExponent.toByteArray())
        val body = """{"keys":[{"kty":"RSA","kid":"k1","alg":"RS256","n":"$n","e":"$e"},{"kty":"EC","kid":"k2"}]}"""

        val keys = parseJwks(body)

        assertEquals(setOf("k1"), keys.keys)
        assertEquals(public.modulus, keys.getValue("k1").modulus)
    }
}
