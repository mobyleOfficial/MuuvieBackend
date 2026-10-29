package org.mobyle.data.remote.translation

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

interface TranslationDataSource {
    /** [text] in Brazilian Portuguese; [sourceLanguage] (ISO 639-1) is detected when null. */
    suspend fun toPortuguese(text: String, sourceLanguage: String?): String
}

/** DeepL API v2. Only registered when DEEPL_API_KEY is set. */
class DeepLTranslationDataSource(private val httpClient: HttpClient, private val apiKey: String) : TranslationDataSource {

    // Free keys end in ":fx" and have a host of their own.
    private val baseUrl = if (apiKey.endsWith(":fx")) "https://api-free.deepl.com" else "https://api.deepl.com"

    override suspend fun toPortuguese(text: String, sourceLanguage: String?): String {
        val response = httpClient.post("$baseUrl/v2/translate") {
            header(HttpHeaders.Authorization, "DeepL-Auth-Key $apiKey")
            contentType(ContentType.Application.Json)
            setBody(DeepLRequest(text = listOf(text), targetLang = "PT-BR", sourceLang = sourceLanguage?.uppercase()))
        }.body<DeepLResponse>()
        return response.translations.firstOrNull()?.text?.takeIf { it.isNotBlank() }
            ?: error("DeepL returned no translation")
    }
}

@Serializable
private data class DeepLRequest(
    val text: List<String>,
    @SerialName("target_lang") val targetLang: String,
    @SerialName("source_lang") val sourceLang: String? = null
)

@Serializable
private data class DeepLResponse(val translations: List<DeepLTranslation> = emptyList())

@Serializable
private data class DeepLTranslation(val text: String)
