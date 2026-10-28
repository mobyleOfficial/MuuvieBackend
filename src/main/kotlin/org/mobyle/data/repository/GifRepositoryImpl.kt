package org.mobyle.data.repository

import kotlinx.coroutines.CancellationException
import org.mobyle.data.remote.gifs.GifDataSource
import org.mobyle.domain.model.Cursor
import org.mobyle.domain.model.Gif
import org.mobyle.domain.model.UnavailableException
import org.mobyle.domain.repository.GifRepository
import org.slf4j.LoggerFactory

/** Without a provider key, or when the provider fails, the search is off and answers 503. */
class GifRepositoryImpl(private val gifDataSource: GifDataSource?) : GifRepository {
    private val log = LoggerFactory.getLogger(GifRepositoryImpl::class.java)

    override suspend fun search(query: String, cursor: String?, limit: Int): Cursor<Gif> {
        val dataSource = gifDataSource ?: throw unavailable("GIF search is not configured")
        return try {
            dataSource.search(query, cursor, limit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("GIF search failed: ${e.message}")
            throw unavailable("GIF search is unavailable")
        }
    }

    private fun unavailable(message: String) = UnavailableException("gif_search_unavailable", message)
}
