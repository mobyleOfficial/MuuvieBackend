package org.mobyle.domain.model

import kotlinx.serialization.Serializable

/** Page-number pagination envelope of the v1 API (research/decisions/api-v1-contract.md). */
@Serializable
data class Page<T>(
    val results: List<T>,
    val page: Int,
    val totalPages: Int,
    val totalResults: Int,
    val hasNext: Boolean
) {
    companion object {
        fun <T> of(results: List<T>, request: PageRequest, totalResults: Int): Page<T> {
            val totalPages = (totalResults + request.limit - 1) / request.limit
            return Page(
                results = results,
                page = request.page,
                totalPages = totalPages,
                totalResults = totalResults,
                hasNext = request.page < totalPages
            )
        }
    }
}

/** Cursor pagination envelope for feeds. `nextCursor` is null on the last page. */
@Serializable
data class Cursor<T>(
    val results: List<T>,
    val nextCursor: String?
)

/** Validated `page` (1-based) and `limit` query parameters. */
data class PageRequest(val page: Int = 1, val limit: Int = DEFAULT_LIMIT) {
    init {
        require(page >= 1) { "page must be >= 1" }
        require(limit in 1..MAX_LIMIT) { "limit must be between 1 and $MAX_LIMIT" }
    }

    val offset: Long get() = (page - 1).toLong() * limit

    companion object {
        const val DEFAULT_LIMIT = 20
        const val MAX_LIMIT = 50
    }
}
