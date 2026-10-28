package org.mobyle.domain.repository

import org.mobyle.domain.model.Cursor
import org.mobyle.domain.model.Gif

/** GIF search through the server, so the provider key never reaches the app (F2.2). */
interface GifRepository {
    suspend fun search(query: String, cursor: String?, limit: Int): Cursor<Gif>
}
