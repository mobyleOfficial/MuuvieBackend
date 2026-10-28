package org.mobyle.domain.usecase.shelves

import org.mobyle.domain.model.ShelfTransferMode
import org.mobyle.domain.model.ValidationException
import org.mobyle.domain.repository.ShelfRepository

/** Moves or copies movies (all of them when [movieIds] is empty) to another of the user's shelves (F1.5). */
class TransferShelfMovies(private val repository: ShelfRepository, private val access: ShelfAccess) {
    operator fun invoke(userId: String, shelfId: Long, targetShelfId: Long, mode: ShelfTransferMode, movieIds: List<Long>) {
        if (shelfId == targetShelfId) throw ValidationException("targetShelfId", "Target must be another shelf")
        val from = access.ownedShelf(userId, shelfId)
        val to = access.ownedShelf(userId, targetShelfId)
        repository.transferMovies(from, to, mode, movieIds.distinct())
    }
}
