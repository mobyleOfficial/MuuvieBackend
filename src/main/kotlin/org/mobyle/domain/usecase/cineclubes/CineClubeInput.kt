package org.mobyle.domain.usecase.cineclubes

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlinx.serialization.Serializable
import org.mobyle.domain.model.CineClubeDraft
import org.mobyle.domain.model.SessionDraft
import org.mobyle.domain.model.ValidationException
import org.mobyle.domain.usecase.maratonas.color
import org.mobyle.domain.usecase.maratonas.date
import org.mobyle.domain.usecase.maratonas.normalizedSlug
import org.mobyle.domain.usecase.maratonas.optional
import org.mobyle.domain.usecase.maratonas.required
import org.mobyle.domain.usecase.maratonas.url

private const val TITLE_MAX_LENGTH = 100
private const val DESCRIPTION_MAX_LENGTH = 2000
const val MAX_SESSIONS = 52
private const val MAX_SESSION_DAYS = 90
// The debate may come a while after the period, for who watches late.
private const val MAX_DEBATE_DELAY_DAYS = 30
private const val MAX_SPAN_DAYS = 366

/**
 * Body of POST /cineclubes and PUT /cineclubes/{slug} (F8.5). One session is a simple
 * CineClube, more make a cycle (F8.6). A session gives its movie by the database id or the
 * TMDB id; without a debate day, the debate is on the last day of the period.
 */
@Serializable
data class CineClubeInput(
    val slug: String = "",
    val title: String = "",
    val description: String? = null,
    val accentColor: String? = null,
    // Null keeps the current password on edit; removePassword takes it away.
    val password: String? = null,
    val removePassword: Boolean = false,
    val debateLink: String? = null,
    val socialLink: String? = null,
    val sessions: List<SessionInput> = emptyList()
)

@Serializable
data class SessionInput(
    val id: Long? = null,
    val movieId: Long? = null,
    val tmdbId: Int? = null,
    // Title of a movie known only on TMDB, for its stub.
    val title: String? = null,
    val startsOn: String = "",
    val endsOn: String = "",
    val debateOn: String? = null
)

/**
 * Validates a CineClube. [movieOf] resolves the movie of a session to its database id.
 * [frozen] are the stored sessions that already started (on edit): they stay as they are,
 * and every other session has to start after [today]. A new CineClube cannot end in the past.
 */
fun cineclubeDraftOf(
    input: CineClubeInput,
    today: LocalDate,
    isNew: Boolean,
    frozen: List<SessionDraft> = emptyList(),
    movieOf: (SessionInput) -> Long
): CineClubeDraft {
    val slug = normalizedSlug(input.slug)
        ?: throw ValidationException("slug", "Slug must have 3 to 50 lowercase letters, digits or hyphens")
    val title = required(input.title, "title", TITLE_MAX_LENGTH)
    val frozenIds = frozen.mapNotNull { it.id }.toSet()
    val sent = input.sessions.filter { it.id == null || it.id !in frozenIds }
    if (sent.size + frozen.size !in 1..MAX_SESSIONS) {
        throw ValidationException("sessions", "A CineClube has between 1 and $MAX_SESSIONS sessions")
    }
    val sessions = frozen + sent.map { session ->
        val startsOn = date(session.startsOn, "sessions")
        val endsOn = date(session.endsOn, "sessions")
        val debateOn = session.debateOn?.takeIf { it.isNotBlank() }?.let { date(it, "sessions") } ?: endsOn
        if (endsOn < startsOn) throw ValidationException("sessions", "A session must end on or after its start")
        if (startsOn.plus(MAX_SESSION_DAYS, DateTimeUnit.DAY) < endsOn) {
            throw ValidationException("sessions", "A session lasts at most $MAX_SESSION_DAYS days")
        }
        if (debateOn < startsOn || endsOn.plus(MAX_DEBATE_DELAY_DAYS, DateTimeUnit.DAY) < debateOn) {
            throw ValidationException("sessions", "The debate is between the start and $MAX_DEBATE_DELAY_DAYS days after the end")
        }
        if (frozen.isNotEmpty() && startsOn <= today) {
            throw ValidationException("sessions", "A new session has to start after today")
        }
        if (session.movieId == null && session.tmdbId == null) {
            throw ValidationException("sessions", "Each session needs a movie")
        }
        SessionDraft(session.id, movieOf(session), startsOn, endsOn, debateOn)
    }
    val ordered = sessions.sortedBy { it.startsOn }
    ordered.zipWithNext().forEach { (previous, next) ->
        if (next.startsOn <= previous.endsOn) throw ValidationException("sessions", "Sessions cannot overlap")
    }
    val draft = CineClubeDraft(
        slug = slug,
        title = title,
        description = optional(input.description, "description", DESCRIPTION_MAX_LENGTH),
        accentColor = color(input.accentColor, "accentColor"),
        debateLink = url(input.debateLink, "debateLink"),
        socialLink = url(input.socialLink, "socialLink"),
        sessions = ordered
    )
    if (draft.startsOn.plus(MAX_SPAN_DAYS, DateTimeUnit.DAY) < draft.endsOn) {
        throw ValidationException("sessions", "A CineClube lasts at most $MAX_SPAN_DAYS days")
    }
    if (isNew && draft.endsOn < today) throw ValidationException("sessions", "The CineClube cannot end in the past")
    return draft
}
