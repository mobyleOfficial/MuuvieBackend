package org.mobyle.domain.usecase.imports

import kotlinx.serialization.Serializable
import org.mobyle.domain.model.ConflictException
import org.mobyle.domain.model.NotFoundException
import org.mobyle.domain.model.Page
import org.mobyle.domain.model.PageRequest
import org.mobyle.domain.model.UnmatchedItem
import org.mobyle.domain.model.ValidationException
import org.mobyle.domain.repository.ImportRepository

private const val PENDING = "pending"

/** POST /imports/{id}/unmatched/{itemId}: the TMDB movie to use, or skip to keep the movie as imported. */
@Serializable
data class ResolveUnmatchedInput(val tmdbId: Int? = null, val title: String? = null, val skip: Boolean = false)

/** F3.4: the movies an import could not match with confidence. */
class GetUnmatchedItems(private val repository: ImportRepository, private val access: ImportAccess) {
    operator fun invoke(userId: String, jobId: Long, page: PageRequest): Page<UnmatchedItem> {
        access.ownedJob(userId, jobId)
        return repository.getUnmatched(jobId, page)
    }
}

/** F3.4: confirms a candidate in one tap, or skips. A skipped movie stays imported as it came. */
class ResolveUnmatchedItem(private val repository: ImportRepository, private val access: ImportAccess) {
    operator fun invoke(userId: String, jobId: Long, itemId: Long, input: ResolveUnmatchedInput): UnmatchedItem {
        val job = access.ownedJob(userId, jobId)
        val item = repository.getUnmatchedItem(itemId)?.takeIf { it.jobId == jobId }
            ?: throw NotFoundException("Item not found")
        if (item.resolution != PENDING) throw ConflictException("already_resolved", "This movie was already resolved")
        if (input.skip) return repository.skipUnmatched(itemId)

        val tmdbId = input.tmdbId?.takeIf { it > 0 } ?: throw ValidationException("tmdbId", "tmdbId or skip is required")
        // Any TMDB movie can be picked; the title is only needed when it is not one of the candidates.
        val title = item.candidates.firstOrNull { it.tmdbId == tmdbId }?.title
            ?: input.title?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ValidationException("title", "title is required for a movie outside the candidates")
        return repository.matchUnmatched(item, job.source, tmdbId, title)
    }
}
