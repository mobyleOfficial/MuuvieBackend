package org.mobyle.domain.usecase.movies

import kotlinx.datetime.LocalDate
import org.mobyle.domain.model.CineClubeFilter
import org.mobyle.domain.model.CineClubeSummary
import org.mobyle.domain.model.Page
import org.mobyle.domain.model.PageRequest
import org.mobyle.domain.repository.CineClubeRepository
import org.mobyle.domain.usecase.watch.today

/** The CineClubes of the movie detail's Comunidade (B13.5): every non-cancelled one with a session of it, finished included. */
class GetMovieCineClubes(
    private val repository: CineClubeRepository,
    private val today: () -> LocalDate = ::today
) {
    operator fun invoke(viewerId: String?, movieId: Long, page: PageRequest): Page<CineClubeSummary> =
        repository.list(viewerId, CineClubeFilter(movieId = movieId, includeFinished = true), page, today())
}
