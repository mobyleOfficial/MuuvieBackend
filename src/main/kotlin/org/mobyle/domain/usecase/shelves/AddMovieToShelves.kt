package org.mobyle.domain.usecase.shelves

import org.mobyle.domain.model.NotFoundException
import org.mobyle.domain.model.ValidationException
import org.mobyle.domain.repository.ShelfRepository

/**
 * Adds one movie to one or more of the user's shelves in a single step (F1.4).
 * The movie is given by its database id or, for movies seen only on TMDB, by its TMDB id.
 * Returns the movie's database id.
 */
class AddMovieToShelves(private val repository: ShelfRepository, private val access: ShelfAccess) {
    operator fun invoke(userId: String, shelfIds: List<Long>, movieId: Long?, tmdbId: Int?, title: String?): Long {
        if (shelfIds.isEmpty()) throw ValidationException("shelfIds", "At least one shelf is required")
        if (movieId == null && tmdbId == null) throw ValidationException("movieId", "movieId or tmdbId is required")
        val shelves = shelfIds.distinct().map { access.ownedShelf(userId, it) }
        val resolvedMovieId = repository.resolveMovieId(movieId, tmdbId, title)
            ?: throw NotFoundException("Movie not found")
        shelves.forEach { repository.addMovie(it, resolvedMovieId) }
        return resolvedMovieId
    }
}
