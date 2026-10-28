package org.mobyle.domain.usecase.movies

import kotlinx.datetime.LocalDate
import org.mobyle.domain.model.CineClubeFilter
import org.mobyle.domain.model.MaratonaFilter
import org.mobyle.domain.model.MovieEvents
import org.mobyle.domain.model.MovieMaratona
import org.mobyle.domain.model.PageRequest
import org.mobyle.domain.policy.ChallengeCriteriaMatcher
import org.mobyle.domain.repository.CineClubeRepository
import org.mobyle.domain.repository.MaratonaCatalog
import org.mobyle.domain.repository.MaratonaRepository
import org.mobyle.domain.usecase.watch.today
import org.slf4j.LoggerFactory

/**
 * "Em quais maratonas e CineClubes este filme está" on the movie detail (F12.1).
 * Maratonas: upcoming or active ones with a challenge whose criteria the movie fits; the
 * viewer's come first. CineClubes: upcoming or active ones with a session of the movie.
 * A failure on one side leaves that side empty and never fails the other.
 */
class GetMovieEvents(
    private val maratonas: MaratonaRepository,
    private val catalog: MaratonaCatalog,
    private val cineclubes: CineClubeRepository,
    private val today: () -> LocalDate = ::today
) {
    private val log = LoggerFactory.getLogger(GetMovieEvents::class.java)

    operator fun invoke(viewerId: String?, movieId: Long): MovieEvents {
        val cineclubeList = runCatching {
            cineclubes.list(viewerId, CineClubeFilter(movieId = movieId), PageRequest(1, MAX_EVENTS), today()).results
                .sortedByDescending { it.joined }
        }.getOrElse { log.warn("[EVENTS] CineClubes of movie $movieId failed", it); emptyList() }
        return MovieEvents(maratonas = maratonasOf(viewerId, movieId), cineclubes = cineclubeList)
    }

    private fun maratonasOf(viewerId: String?, movieId: Long): List<MovieMaratona> = runCatching {
        val open = maratonas.list(viewerId, MaratonaFilter(), PageRequest(1, SCANNED_MARATONAS)).results
        if (open.isEmpty()) return@runCatching emptyList()
        val facts = catalog.facts(movieId) ?: return@runCatching emptyList()
        open.mapNotNull { summary ->
            val maratona = maratonas.getById(summary.id) ?: return@mapNotNull null
            val challenges = maratona.challenges
                .filter { challenge -> challenge.criteria?.let { ChallengeCriteriaMatcher.matches(it, facts) } == true }
                .map { it.description }
            if (challenges.isEmpty()) null else MovieMaratona(summary, challenges)
        }
            .sortedByDescending { it.maratona.joined }
            .take(MAX_EVENTS)
    }.getOrElse { log.warn("[EVENTS] Maratonas of movie $movieId failed", it); emptyList() }

    private companion object {
        const val MAX_EVENTS = 10
        // Open maratonas read to test the criteria; the listing is newest first.
        const val SCANNED_MARATONAS = 50
    }
}
