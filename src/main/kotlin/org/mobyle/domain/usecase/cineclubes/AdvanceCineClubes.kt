package org.mobyle.domain.usecase.cineclubes

import kotlinx.datetime.LocalDate
import org.mobyle.domain.model.SocialEvent
import org.mobyle.domain.model.vocabulary.CineClubeStatus
import org.mobyle.domain.repository.CineClubeRepository
import org.mobyle.domain.repository.SocialNotifier
import org.mobyle.domain.usecase.watch.today

/** What one run of the CineClube scheduler did. */
data class CineClubeAdvance(val started: Int, val finished: Int, val sessionsOpened: Int, val debatesNotified: Int)

/**
 * Moves CineClubes along their sessions, hourly from the scheduler (B8): upcoming ones start
 * with their first session and finish the day after the last end or debate. When a session
 * of a cycle starts, its movie goes to the participants' Quero ver; on the debate day they
 * get a push. A debate day that passed without the scheduler running is marked, not sent.
 */
class AdvanceCineClubes(
    private val repository: CineClubeRepository,
    private val watchlist: CineClubeWatchlist,
    private val notifier: SocialNotifier,
    private val today: () -> LocalDate = ::today
) {
    operator fun invoke(): CineClubeAdvance {
        val now = today()
        val started = repository.dueToStart(now).onEach { repository.setStatus(it, CineClubeStatus.ACTIVE) }
        val opened = repository.sessionsToOpen(now).onEach { due ->
            if (due.session.counts(now)) {
                repository.participantIds(due.cineclubeId).forEach { watchlist.add(it, due.session.movie.id) }
            }
            repository.markOpened(due.session.id)
        }
        val debates = repository.debatesToNotify(now).onEach { due ->
            if (due.session.debateOn == now) {
                val cineclube = repository.getById(due.cineclubeId, now)
                if (cineclube != null) {
                    val event = SocialEvent.CineClubeDebate(cineclube.slug, cineclube.title, due.session.movie.title)
                    repository.participantIds(due.cineclubeId).forEach { notifier.notify(it, event) }
                }
            }
            repository.markDebateNotified(due.session.id)
        }
        val finished = repository.dueToFinish(now).onEach { repository.setStatus(it, CineClubeStatus.FINISHED) }
        return CineClubeAdvance(started.size, finished.size, opened.count { it.session.counts(now) }, debates.count { it.session.debateOn == now })
    }
}
