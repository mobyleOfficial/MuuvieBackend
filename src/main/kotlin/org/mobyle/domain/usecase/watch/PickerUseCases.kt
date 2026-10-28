package org.mobyle.domain.usecase.watch

import org.mobyle.domain.model.Cursor
import org.mobyle.domain.model.Gif
import org.mobyle.domain.model.ValidationException
import org.mobyle.domain.model.vocabulary.Emotion
import org.mobyle.domain.repository.GifRepository
import org.mobyle.domain.repository.WatchRepository

private const val RECENT_LIMIT = 12
private const val QUERY_MAX_LENGTH = 100
private const val GIF_PAGE_SIZE = 20

/** Emotions the user picked last, for the top of the emotion picker (F2.2). */
class GetRecentEmotions(private val repository: WatchRepository) {
    operator fun invoke(userId: String): List<Emotion> = repository.recentEmotions(userId, RECENT_LIMIT)
}

/** GIFs the user picked last (F2.2). */
class GetRecentGifs(private val repository: WatchRepository) {
    operator fun invoke(userId: String): List<Gif> = repository.recentGifs(userId, RECENT_LIMIT)
}

/** GIF search for the session form (F2.2), proxied so the provider key stays on the server. */
class SearchGifs(private val repository: GifRepository) {
    suspend operator fun invoke(query: String, cursor: String?): Cursor<Gif> {
        val trimmed = query.trim()
        if (trimmed.isEmpty() || trimmed.length > QUERY_MAX_LENGTH) {
            throw ValidationException("q", "q must have between 1 and $QUERY_MAX_LENGTH characters")
        }
        return repository.search(trimmed, cursor, GIF_PAGE_SIZE)
    }
}
