package org.mobyle.data.remote.translation

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import io.ktor.client.request.HttpRequestData
import kotlin.test.Test
import kotlin.test.assertEquals

class DeepLTranslationDataSourceTest {

    private val requests = mutableListOf<HttpRequestData>()

    private fun dataSource(apiKey: String) = DeepLTranslationDataSource(
        HttpClient(MockEngine { request ->
            requests += request
            respond(
                """{"translations":[{"detected_source_language":"EN","text":"Dois casais"}]}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        },
        apiKey
    )

    @Test
    fun `asks for Brazilian Portuguese with the key in the header`() = runBlocking {
        val text = dataSource("key:fx").toPortuguese("Two couples", "en")

        assertEquals("Dois casais", text)
        val request = requests.single()
        assertEquals("DeepL-Auth-Key key:fx", request.headers[HttpHeaders.Authorization])
        val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
        assertEquals("PT-BR", body["target_lang"]!!.jsonPrimitive.content)
        assertEquals("EN", body["source_lang"]!!.jsonPrimitive.content)
        assertEquals("Two couples", body["text"]!!.jsonArray.single().jsonPrimitive.content)
    }

    @Test
    fun `free keys go to the free host, the others to the paid one`() = runBlocking {
        dataSource("key:fx").toPortuguese("Two couples", "en")
        dataSource("key").toPortuguese("Two couples", "en")

        assertEquals(listOf("api-free.deepl.com", "api.deepl.com"), requests.map { it.url.host })
    }
}
