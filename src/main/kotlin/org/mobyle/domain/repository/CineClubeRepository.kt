package org.mobyle.domain.repository

import kotlinx.datetime.LocalDate
import org.mobyle.domain.model.CineClube
import org.mobyle.domain.model.CineClubeDraft
import org.mobyle.domain.model.CineClubeFilter
import org.mobyle.domain.model.CineClubeLog
import org.mobyle.domain.model.CineClubeParticipant
import org.mobyle.domain.model.CineClubeParticipation
import org.mobyle.domain.model.CineClubeSession
import org.mobyle.domain.model.CineClubeSummary
import org.mobyle.domain.model.DueSession
import org.mobyle.domain.model.Page
import org.mobyle.domain.model.PageRequest
import org.mobyle.domain.model.PasswordChange
import org.mobyle.domain.model.WatchProvider
import org.mobyle.domain.model.vocabulary.CineClubeStatus

/**
 * CineClubes (B8): the groups, their sessions and participations. The progress of a
 * participant comes from their watch logs of the session movie within the session period.
 */
interface CineClubeRepository {
    fun slugTaken(slug: String): Boolean

    /** Creates the CineClube with its sessions; returns its id. */
    fun create(ownerId: String, draft: CineClubeDraft, status: CineClubeStatus, passwordHash: String?): Long

    /**
     * Saves [draft]: sessions with an id are updated, new ones inserted, and stored ones
     * missing from the draft deleted, except the ids in [keepSessionIds] (sessions that started).
     */
    fun update(id: Long, draft: CineClubeDraft, keepSessionIds: Set<Long>, password: PasswordChange)

    /** The CineClube with its sessions, without the viewer-dependent fields. [today] picks the current session. */
    fun get(slug: String, today: LocalDate): CineClube?

    fun getById(id: Long, today: LocalDate): CineClube?

    fun passwordHash(id: Long): String?

    fun list(viewerId: String?, filter: CineClubeFilter, page: PageRequest, today: LocalDate): Page<CineClubeSummary>

    fun setStatus(id: Long, status: CineClubeStatus)

    fun participation(cineclubeId: Long, userId: String): CineClubeParticipation?

    fun join(cineclubeId: Long, userId: String): CineClubeParticipation

    fun leave(participationId: Long)

    /** Every participant's external id. */
    fun participantIds(cineclubeId: Long): List<String>

    /**
     * Participants with their progress in [session]. [completedSessions] counts, for each
     * participant, the sessions of [sessions] whose movie they completed within the period.
     */
    fun participants(cineclubeId: Long, session: CineClubeSession, sessions: List<CineClubeSession>): List<CineClubeParticipant>

    /**
     * Logs of the participants for the movie of [session] within its period, newest first,
     * without the gate applied. Only logs [viewerId] may see by their visibility.
     */
    fun sessionLogs(cineclubeId: Long, session: CineClubeSession, viewerId: String?, page: PageRequest): Page<CineClubeLog>

    /** Whether [userId] already finished [movieId]: a completed log or the movie on Já assisti. */
    fun hasWatched(userId: String, movieId: Long): Boolean

    /** Sessions of upcoming or active CineClubes that started by [today] and were not opened yet. */
    fun sessionsToOpen(today: LocalDate): List<DueSession>

    fun markOpened(sessionId: Long)

    /** Sessions of active CineClubes whose debate day is [today] or passed, not notified yet. */
    fun debatesToNotify(today: LocalDate): List<DueSession>

    fun markDebateNotified(sessionId: Long)

    fun dueToStart(today: LocalDate): List<Long>

    fun dueToFinish(today: LocalDate): List<Long>

    /** CineClubes [userId] takes part in, cancelled ones left out, for the profile counter. */
    fun participationCount(userId: String): Int
}

/** Where to watch the movie of a CineClube, from the TMDB watch providers the catalog keeps. */
interface CineClubeCatalog {
    suspend fun watchProviders(tmdbId: Int): List<WatchProvider>
}
