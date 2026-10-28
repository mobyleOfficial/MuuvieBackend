package org.mobyle.domain.model

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import org.mobyle.domain.model.vocabulary.CineClubeStatus
import org.mobyle.domain.model.vocabulary.Emotion
import org.mobyle.domain.model.vocabulary.SessionProgress
import org.mobyle.domain.model.vocabulary.WatchMode

/**
 * A CineClube (B8): a group that watches one movie in a period and meets on a debate day.
 * A cycle has several sessions in sequence, one movie each. [currentSessionId] is the session
 * running today, else the next one, else the last. [watchProviders] are for the movie of the
 * current session; [me] is the viewer's participation and [canEdit] is true for the owner.
 */
@Serializable
data class CineClube(
    val id: Long,
    val slug: String,
    val title: String,
    val description: String?,
    val accentColor: String?,
    val status: CineClubeStatus,
    val hasPassword: Boolean,
    val debateLink: String?,
    val socialLink: String?,
    val isCycle: Boolean,
    val startsOn: LocalDate,
    val endsOn: LocalDate,
    val owner: UserSummary,
    val sessions: List<CineClubeSession>,
    val currentSessionId: Long?,
    val totalParticipants: Int,
    val createdAt: Instant,
    val watchProviders: List<WatchProvider> = emptyList(),
    val me: CineClubeMembership? = null,
    val canEdit: Boolean = false
) {
    fun isOwner(userId: String) = owner.id == userId

    val currentSession: CineClubeSession? get() = sessions.firstOrNull { it.id == currentSessionId }
}

/** One movie of a CineClube with its period and debate day. */
@Serializable
data class CineClubeSession(
    val id: Long,
    val position: Int,
    val movie: CineClubeMovie,
    val startsOn: LocalDate,
    val endsOn: LocalDate,
    val debateOn: LocalDate
) {
    /** Logs from the start to the end or the debate day, whichever is later, count for the session. */
    val lastDay: LocalDate get() = maxOf(endsOn, debateOn)

    fun counts(day: LocalDate) = day >= startsOn && day <= lastDay

    companion object {
        /** The session running on [today], else the next one, else the last one. */
        fun current(sessions: List<CineClubeSession>, today: LocalDate): CineClubeSession? =
            sessions.firstOrNull { it.counts(today) }
                ?: sessions.filter { it.startsOn > today }.minByOrNull { it.startsOn }
                ?: sessions.maxByOrNull { it.startsOn }
    }
}

@Serializable
data class CineClubeMovie(
    val id: Long,
    val tmdbId: Int?,
    val title: String,
    val posterPath: String?,
    val year: Int?,
    val runtime: Int?
)

/** A row of the CineClube list (F8.2); the movie and the dates are the current session's. */
@Serializable
data class CineClubeSummary(
    val id: Long,
    val slug: String,
    val title: String,
    val accentColor: String?,
    val status: CineClubeStatus,
    val hasPassword: Boolean,
    val isCycle: Boolean,
    val owner: UserSummary,
    val movie: CineClubeMovie?,
    val startsOn: LocalDate,
    val endsOn: LocalDate,
    val debateOn: LocalDate?,
    val totalSessions: Int,
    val totalParticipants: Int,
    val joined: Boolean
)

/** The viewer's participation, as the detail shows it: their progress in the current session. */
@Serializable
data class CineClubeMembership(
    val joinedAt: Instant,
    val progress: SessionProgress,
    val completedSessions: Int
)

/** A participation as the use cases need it. */
data class CineClubeParticipation(val id: Long, val cineclubeId: Long, val userId: String, val joinedAt: Instant)

/** A participant of the list (F8.3) with their progress in the session asked. */
@Serializable
data class CineClubeParticipant(
    val user: UserSummary,
    val progress: SessionProgress,
    val completedSessions: Int,
    val lastWatchedOn: LocalDate?,
    val joinedAt: Instant
)

/**
 * A watch log of a participant in the progress tab (F8.3). When [hidden], the comment and the
 * GIF were taken out by the spoiler gate: the viewer has not finished the movie and the
 * debate day has not come.
 */
@Serializable
data class CineClubeLog(
    val id: Long,
    val user: UserSummary,
    val sessionId: Long,
    val watchedOn: LocalDate,
    val mode: WatchMode,
    val minutesWatched: Int?,
    val percent: Int?,
    val completed: Boolean,
    val emotions: List<Emotion>,
    val gifUrl: String?,
    val comment: String?,
    val hasSpoiler: Boolean,
    val hidden: Boolean,
    val hiddenReason: String?,
    val createdAt: Instant
)

/**
 * Query of the CineClube list (F8.2): upcoming and running ones, plus finished ones with
 * [includeFinished]. [open] keeps the ones without a password; [mine] lists the ones the
 * viewer owns and [participant] the ones a user joined, in any status but cancelled ones
 * only for [mine].
 */
data class CineClubeFilter(
    val query: String? = null,
    val open: Boolean = false,
    val includeFinished: Boolean = false,
    val mine: Boolean = false,
    val participant: String? = null,
    // Only CineClubes with a session of this movie (the movie detail, F12.1).
    val movieId: Long? = null
)

/** Fields of a CineClube, already validated. Sessions with an id update the stored one. */
data class CineClubeDraft(
    val slug: String,
    val title: String,
    val description: String?,
    val accentColor: String?,
    val debateLink: String?,
    val socialLink: String?,
    val sessions: List<SessionDraft>
) {
    val isCycle: Boolean get() = sessions.size > 1
    val startsOn: LocalDate get() = sessions.minOf { it.startsOn }
    val endsOn: LocalDate get() = sessions.maxOf { maxOf(it.endsOn, it.debateOn) }
}

data class SessionDraft(
    val id: Long?,
    val movieId: Long,
    val startsOn: LocalDate,
    val endsOn: LocalDate,
    val debateOn: LocalDate
)

/** A session the scheduler has to act on, with the CineClube it belongs to. */
data class DueSession(val cineclubeId: Long, val session: CineClubeSession)
