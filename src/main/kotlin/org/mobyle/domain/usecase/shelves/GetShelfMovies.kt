package org.mobyle.domain.usecase.shelves

import org.mobyle.domain.model.Page
import org.mobyle.domain.model.PageRequest
import org.mobyle.domain.model.ShelfMovie
import org.mobyle.domain.model.ShelfMovieFilter
import org.mobyle.domain.repository.ShelfRepository

class GetShelfMovies(private val repository: ShelfRepository, private val access: ShelfAccess) {
    operator fun invoke(viewerId: String?, shelfId: Long, filter: ShelfMovieFilter, page: PageRequest): Page<ShelfMovie> {
        val shelf = access.visibleShelf(viewerId, shelfId)
        return repository.getShelfMovies(shelf, filter, page)
    }
}
