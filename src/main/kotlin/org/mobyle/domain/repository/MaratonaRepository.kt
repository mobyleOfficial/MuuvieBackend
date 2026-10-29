package org.mobyle.domain.repository

import kotlinx.datetime.LocalDate
import org.mobyle.domain.model.ChallengeLink
import org.mobyle.domain.model.Finisher
import org.mobyle.domain.model.Maratona
import org.mobyle.domain.model.MaratonaDraft
import org.mobyle.domain.model.MaratonaFilter
import org.mobyle.domain.model.MaratonaSummary
import org.mobyle.domain.model.Page
import org.mobyle.domain.model.PageRequest
import org.mobyle.domain.model.Participation
import org.mobyle.domain.model.PasswordChange
import org.mobyle.domain.model.RankingEntry
import org.mobyle.domain.model.TeamRankingEntry
import org.mobyle.domain.model.vocabulary.ChallengeLinkSource
import org.mobyle.domain.model.vocabulary.MaratonaStatus

/** Maratonas (B7): the events, their participations and the challenge links. */
interface MaratonaRepository {
    fun slugTaken(slug: String): Boolean

    /** Creates the maratona with all its parts; returns its id. */
    fun create(ownerId: String, draft: MaratonaDraft, status: MaratonaStatus, passwordHash: String?): Long

    /**
     * Saves [draft]. With [structure] false only the texts, logo, color, link and prizes change
     * (a maratona that started); otherwise categories, challenges, levels and teams are replaced:
     * items with an id are updated, new ones inserted and missing ones deleted.
     */
    fun update(id: Long, draft: MaratonaDraft, structure: Boolean, password: PasswordChange)

    /** The maratona with its parts, without the viewer-dependent fields. */
    fun get(slug: String): Maratona?

    fun getById(id: Long): Maratona?

    fun passwordHash(id: Long): String?

    fun list(viewerId: String?, filter: MaratonaFilter, page: PageRequest): Page<MaratonaSummary>

    fun setStatus(id: Long, status: MaratonaStatus)

    fun setCoOwners(id: Long, userIds: List<String>)

    fun participation(maratonaId: Long, userId: String): Participation?

    fun join(maratonaId: Long, userId: String, levelId: Long, teamId: Long?, queueShelfId: Long): Participation

    fun leave(participationId: Long)

    fun links(participationId: Long): List<ChallengeLink>

    fun completedChallenges(participationId: Long): Int

    /** Ties [movieId] to the challenge; false when it already was. */
    fun addLink(participationId: Long, challengeId: Long, movieId: Long, watchLogId: Long?, source: ChallengeLinkSource): Boolean

    /** False when there was no such link. */
    fun removeLink(participationId: Long, challengeId: Long, movieId: Long): Boolean

    /** Active maratonas [userId] takes part in whose period contains [on]. */
    fun activeParticipations(userId: String, on: LocalDate): List<Pair<Participation, Maratona>>

    fun ranking(maratonaId: Long, page: PageRequest): Page<RankingEntry>

    fun teamRanking(maratonaId: Long): List<TeamRankingEntry>

    /** Upcoming maratonas whose start arrived, and active ones whose end passed. */
    fun dueToStart(today: LocalDate): List<Long>

    fun dueToFinish(today: LocalDate): List<Long>

    /** Participants of a maratona who reached their level (or the lowest one, without a level). */
    fun finishers(maratonaId: Long): List<Finisher>

    /** External ids of everyone who joined a maratona, for its notifications (B15.1). */
    fun participantIds(maratonaId: Long): List<String>

    /** Maratonas [userId] created and maratonas they take part in, for the profile counters. */
    fun createdCount(userId: String): Int

    fun participationCount(userId: String): Int
}
