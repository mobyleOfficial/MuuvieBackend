package org.mobyle.domain.usecase.maratonas

import kotlinx.datetime.LocalDate
import org.mobyle.domain.model.SocialEvent
import org.mobyle.domain.model.vocabulary.MaratonaStatus
import org.mobyle.domain.repository.MaratonaRepository
import org.mobyle.domain.repository.SocialNotifier
import org.mobyle.domain.usecase.feed.ActivityPublisher
import org.mobyle.domain.usecase.watch.today

/**
 * Moves maratonas along their period (F7.8), hourly from MaratonaScheduler: upcoming ones
 * start on starts_on, active ones finish the day after ends_on, and whoever reached their
 * level gets a "concluiu maratona". The participants get a push at the start and at the end
 * (B15.1); a maratona created already active never "starts", so it sends no start push.
 */
class AdvanceMaratonas(
    private val repository: MaratonaRepository,
    private val activities: ActivityPublisher,
    private val notifier: SocialNotifier,
    private val today: () -> LocalDate = ::today
) {
    /** How many maratonas started and finished. */
    operator fun invoke(): Pair<Int, Int> {
        val now = today()
        val started = repository.dueToStart(now).onEach { id ->
            repository.setStatus(id, MaratonaStatus.ACTIVE)
            val maratona = repository.getById(id) ?: return@onEach
            val event = SocialEvent.MaratonaStarted(maratona.slug, maratona.title)
            repository.participantIds(id).forEach { notifier.notify(it, event) }
        }
        val finished = repository.dueToFinish(now).onEach { id ->
            repository.setStatus(id, MaratonaStatus.FINISHED)
            val maratona = repository.getById(id) ?: return@onEach
            repository.finishers(id).forEach { activities.maratonaFinished(it.userId, maratona, it.level, it.completed) }
            val event = SocialEvent.MaratonaFinished(maratona.slug, maratona.title)
            repository.participantIds(id).forEach { notifier.notify(it, event) }
        }
        return started.size to finished.size
    }
}
