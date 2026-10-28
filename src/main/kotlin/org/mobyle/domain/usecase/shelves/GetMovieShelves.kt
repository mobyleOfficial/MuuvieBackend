package org.mobyle.domain.usecase.shelves

import kotlinx.serialization.Serializable
import org.mobyle.domain.repository.ShelfRepository

/** Which of the user's shelves hold a movie. [movieId] is null for a movie no one has saved yet. */
@Serializable
data class MovieShelves(val movieId: Long?, val shelfIds: List<Long>)

class GetMovieShelves(private val repository: ShelfRepository) {
    operator fun invoke(userId: String, movieId: Long?, tmdbId: Int?): MovieShelves {
        repository.ensureSystemShelves(userId)
        val resolvedMovieId = movieId ?: tmdbId?.let { repository.findMovieIdByTmdbId(it) }
            ?: return MovieShelves(movieId = null, shelfIds = emptyList())
        return MovieShelves(resolvedMovieId, repository.shelfIdsWithMovie(userId, resolvedMovieId))
    }
}
