package org.mobyle.data.remote.gifs

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.mobyle.data.repository.GifRepositoryImpl
import org.mobyle.domain.model.UnavailableException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class TenorGifDataSourceTest {

    private fun client(status: HttpStatusCode, body: String, onRequest: (String) -> Unit = {}) =
        HttpClient(MockEngine { request ->
            onRequest(request.url.toString())
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }) {
            expectSuccess = true
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }

    @Test
    fun `search parses gifs, previews and the next cursor`() = runBlocking {
        var url = ""
        val body = """
            {"results": [
              {"id": "1", "content_description": "x",
               "media_formats": {"gif": {"url": "https://media.tenor.com/1.gif", "dims": [1, 1]},
                                 "tinygif": {"url": "https://media.tenor.com/1-tiny.gif"}}},
              {"id": "2", "media_formats": {"mp4": {"url": "https://media.tenor.com/2.mp4"}}}
            ], "next": "CAgQ"}
        """.trimIndent()
        val tenor = TenorGifDataSource(client(HttpStatusCode.OK, body) { url = it }, apiKey = "key")

        val page = tenor.search("cinema", cursor = "abc", limit = 20)

        assertEquals(listOf("1"), page.results.map { it.id }) // no gif format: skipped
        assertEquals("https://media.tenor.com/1-tiny.gif", page.results.single().previewUrl)
        assertEquals("CAgQ", page.nextCursor)
        listOf("q=cinema", "key=key", "client_key=muuvie", "limit=20", "pos=abc").forEach {
            kotlin.test.assertTrue(it in url, "missing $it in $url")
        }
    }

    @Test
    fun `the last page has no cursor`() = runBlocking {
        val tenor = TenorGifDataSource(client(HttpStatusCode.OK, """{"results": [], "next": ""}"""), apiKey = "key")

        assertNull(tenor.search("cinema", cursor = null, limit = 20).nextCursor)
    }

    @Test
    fun `a provider error answers 503 like a missing key`() {
        val tenor = TenorGifDataSource(client(HttpStatusCode.Forbidden, """{"error": "bad key"}"""), apiKey = "bad")

        val error = assertFailsWith<UnavailableException> {
            runBlocking { GifRepositoryImpl(tenor).search("cinema", null, 20) }
        }
        assertEquals("gif_search_unavailable", error.code)
    }
}
