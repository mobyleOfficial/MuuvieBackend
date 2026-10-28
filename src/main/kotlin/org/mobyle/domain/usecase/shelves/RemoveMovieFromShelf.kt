package org.mobyle.domain.usecase.shelves

import org.mobyle.domain.repository.ShelfRepository

/** Removes a movie from a shelf. Removing it from Já assisti unmarks it as watched; its history stays. */
class RemoveMovieFromShelf(private val repository: ShelfRepository, private val access: ShelfAccess) {
    operator fun invoke(userId: String, shelfId: Long, movieId: Long) {
        val shelf = access.ownedShelf(userId, shelfId)
        repository.removeMovie(shelf, movieId)
    }
}
