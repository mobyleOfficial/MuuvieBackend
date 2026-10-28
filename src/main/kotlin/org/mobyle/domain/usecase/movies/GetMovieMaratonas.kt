package org.mobyle.domain.usecase.movies

import org.mobyle.domain.model.MaratonaFilter
import org.mobyle.domain.model.MaratonaSummary
import org.mobyle.domain.model.Page
import org.mobyle.domain.model.PageRequest
import org.mobyle.domain.repository.MaratonaRepository

/**
 * The maratonas of the movie detail's Comunidade (B13.5): non-cancelled ones, finished
 * included, where the movie completed a challenge for someone.
 */
class GetMovieMaratonas(private val repository: MaratonaRepository) {
    operator fun invoke(viewerId: String?, movieId: Long, page: PageRequest): Page<MaratonaSummary> =
        repository.list(viewerId, MaratonaFilter(movieId = movieId, includeFinished = true), page)
}
