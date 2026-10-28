package org.mobyle.data.remote.gifs

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.mobyle.domain.model.Cursor
import org.mobyle.domain.model.Gif

interface GifDataSource {
    suspend fun search(query: String, cursor: String?, limit: Int): Cursor<Gif>
}

/** Tenor API v2 search. Only registered when TENOR_API_KEY is set. */
class TenorGifDataSource(private val httpClient: HttpClient, private val apiKey: String) : GifDataSource {

    override suspend fun search(query: String, cursor: String?, limit: Int): Cursor<Gif> {
        val response = httpClient.get("https://tenor.googleapis.com/v2/search") {
            parameter("q", query)
            parameter("key", apiKey)
            parameter("client_key", "muuvie")
            parameter("limit", limit)
            parameter("locale", "pt_BR")
            parameter("contentfilter", "medium")
            parameter("media_filter", "gif,tinygif")
            if (cursor != null) parameter("pos", cursor)
        }.body<TenorSearchResponse>()
        val gifs = response.results.mapNotNull { result ->
            val url = result.mediaFormats["gif"]?.url ?: return@mapNotNull null
            Gif(id = result.id, url = url, previewUrl = result.mediaFormats["tinygif"]?.url)
        }
        return Cursor(gifs, nextCursor = response.next?.takeIf { it.isNotBlank() && it != "0" })
    }
}

@Serializable
private data class TenorSearchResponse(val results: List<TenorResult> = emptyList(), val next: String? = null)

@Serializable
private data class TenorResult(
    val id: String,
    @SerialName("media_formats") val mediaFormats: Map<String, TenorMedia> = emptyMap()
)

@Serializable
private data class TenorMedia(val url: String)
