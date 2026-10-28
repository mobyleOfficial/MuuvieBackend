package org.mobyle.domain.usecase.maratonas

import kotlinx.datetime.LocalDate
import org.mobyle.domain.model.vocabulary.MaratonaStatus
import org.mobyle.domain.repository.MaratonaRepository
import org.mobyle.domain.usecase.feed.ActivityPublisher
import org.mobyle.domain.usecase.watch.today

/**
 * Moves maratonas along their period (F7.8), hourly from MaratonaScheduler: upcoming ones
 * start on starts_on, active ones finish the day after ends_on, and whoever reached their
 * level gets a "concluiu maratona".
 */
class AdvanceMaratonas(
    private val repository: MaratonaRepository,
    private val activities: ActivityPublisher,
    private val today: () -> LocalDate = ::today
) {
    /** How many maratonas started and finished. */
    operator fun invoke(): Pair<Int, Int> {
        val now = today()
        val started = repository.dueToStart(now).onEach { repository.setStatus(it, MaratonaStatus.ACTIVE) }
        val finished = repository.dueToFinish(now).onEach { id ->
            repository.setStatus(id, MaratonaStatus.FINISHED)
            val maratona = repository.getById(id) ?: return@onEach
            repository.finishers(id).forEach { activities.maratonaFinished(it.userId, maratona, it.level, it.completed) }
        }
        return started.size to finished.size
    }
}
