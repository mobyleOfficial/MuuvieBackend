package org.mobyle.domain.usecase.shelves

import org.mobyle.domain.model.Page
import org.mobyle.domain.model.PageRequest
import org.mobyle.domain.model.PublicShelf
import org.mobyle.domain.repository.ShelfRepository

/** "Está em N estantes" of the movie detail (B13.2): public custom shelves of anyone. */
class GetPublicShelvesWithMovie(private val repository: ShelfRepository) {
    operator fun invoke(movieId: Long, page: PageRequest): Page<PublicShelf> =
        repository.publicShelvesWithMovie(movieId, page)
}
