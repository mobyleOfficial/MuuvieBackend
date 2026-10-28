package org.mobyle.domain.usecase.maratonas

import org.mobyle.domain.model.WatchLog
import org.mobyle.domain.model.vocabulary.ChallengeLinkSource
import org.mobyle.domain.policy.ChallengeCriteriaMatcher
import org.mobyle.domain.repository.MaratonaCatalog
import org.mobyle.domain.repository.MaratonaRepository
import org.mobyle.domain.usecase.feed.ActivityPublisher
import org.slf4j.LoggerFactory

/**
 * Completes challenges by their criteria when a session is logged (F7.5): a completed session
 * watched within the period of an active maratona the user takes part in. Imports never come
 * through here. A failure is logged and never fails the session.
 */
class MaratonaAutoLinker(
    private val repository: MaratonaRepository,
    private val catalog: MaratonaCatalog,
    private val activities: ActivityPublisher
) {
    private val log = LoggerFactory.getLogger(MaratonaAutoLinker::class.java)

    fun sessionLogged(userId: String, watchLog: WatchLog) {
        try {
            if (!watchLog.completed) return
            val participations = repository.activeParticipations(userId, watchLog.watchedOn)
                .filter { (_, maratona) -> maratona.challenges.any { it.criteria != null } }
            if (participations.isEmpty()) return
            val facts = catalog.facts(watchLog.movieId) ?: return
            participations.forEach { (participation, maratona) ->
                val done = repository.links(participation.id).map { it.challengeId }.toSet()
                maratona.challenges
                    .filter { challenge -> challenge.criteria?.let { ChallengeCriteriaMatcher.matches(it, facts) } == true }
                    .forEach { challenge ->
                        val added = repository.addLink(
                            participation.id, challenge.id, watchLog.movieId, watchLog.id, ChallengeLinkSource.AUTO
                        )
                        if (added && challenge.id !in done) {
                            repository.links(participation.id)
                                .firstOrNull { it.challengeId == challenge.id && it.movieId == watchLog.movieId }
                                ?.let { activities.challengeCompleted(userId, maratona, challenge, it) }
                        }
                    }
            }
        } catch (e: Exception) {
            log.error("[MARATONA] Could not check the challenges of a session", e)
        }
    }
}
