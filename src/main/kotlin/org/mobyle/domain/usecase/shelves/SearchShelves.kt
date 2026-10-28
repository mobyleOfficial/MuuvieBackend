package org.mobyle.domain.usecase.shelves

import org.mobyle.domain.model.Page
import org.mobyle.domain.model.PageRequest
import org.mobyle.domain.model.Shelf
import org.mobyle.domain.repository.ShelfRepository

/** Searches the user's own shelves by name. A blank query lists them all. */
class SearchShelves(private val repository: ShelfRepository) {
    operator fun invoke(userId: String, query: String, page: PageRequest): Page<Shelf> {
        repository.ensureSystemShelves(userId)
        val shelves = repository.searchShelves(userId, query.trim(), page)
        return shelves.copy(results = shelves.results.map { it.copy(self = true) })
    }
}
