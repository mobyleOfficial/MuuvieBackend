package org.mobyle.domain.usecase.cineclubes

import kotlinx.datetime.LocalDate
import org.mobyle.domain.model.CineClube
import org.mobyle.domain.model.CineClubeFilter
import org.mobyle.domain.model.CineClubeLog
import org.mobyle.domain.model.CineClubeMembership
import org.mobyle.domain.model.CineClubeParticipant
import org.mobyle.domain.model.CineClubeSession
import org.mobyle.domain.model.CineClubeSummary
import org.mobyle.domain.model.ConflictException
import org.mobyle.domain.model.ForbiddenException
import org.mobyle.domain.model.NotFoundException
import org.mobyle.domain.model.Page
import org.mobyle.domain.model.PageRequest
import org.mobyle.domain.model.PasswordChange
import org.mobyle.domain.model.SessionDraft
import org.mobyle.domain.model.vocabulary.CineClubeStatus
import org.mobyle.domain.model.vocabulary.SessionProgress
import org.mobyle.domain.model.vocabulary.SystemShelfType
import org.mobyle.domain.model.vocabulary.Visibility
import org.mobyle.domain.repository.CineClubeCatalog
import org.mobyle.domain.repository.CineClubeRepository
import org.mobyle.domain.repository.PasswordHasher
import org.mobyle.domain.repository.ShelfRepository
import org.mobyle.domain.usecase.feed.ActivityPublisher
import org.mobyle.domain.usecase.maratonas.SlugAvailability
import org.mobyle.domain.usecase.maratonas.maratonaPassword
import org.mobyle.domain.usecase.maratonas.normalizedSlug
import org.mobyle.domain.usecase.watch.today
import org.slf4j.LoggerFactory

const val SPOILER_REASON = "spoiler"

/** Loads CineClubes and checks who may change them. */
class CineClubeAccess(private val repository: CineClubeRepository, private val today: () -> LocalDate = ::today) {
    fun get(slug: String): CineClube =
        repository.get(slug.lowercase(), today()) ?: throw NotFoundException("CineClube not found")

    /** The CineClube as [viewerId] sees it: their participation and whether they edit it. */
    fun view(viewerId: String?, slug: String): CineClube {
        val cineclube = get(slug)
        if (viewerId == null) return cineclube
        val participation = repository.participation(cineclube.id, viewerId)
        val membership = participation?.let {
            val session = cineclube.currentSession
            val me = session?.let { s -> repository.participants(cineclube.id, s, cineclube.sessions).firstOrNull { p -> p.user.id == viewerId } }
            CineClubeMembership(it.joinedAt, me?.progress ?: SessionProgress.PENDING, me?.completedSessions ?: 0)
        }
        return cineclube.copy(canEdit = cineclube.isOwner(viewerId), me = membership)
    }

    /** Only the owner. */
    fun owned(userId: String, slug: String): CineClube {
        val cineclube = get(slug)
        if (!cineclube.isOwner(userId)) throw ForbiddenException("not_cineclube_owner", "Only the owner can change this CineClube")
        return cineclube
    }

    /** A session of the CineClube by id, or the current one. */
    fun session(cineclube: CineClube, sessionId: Long?): CineClubeSession =
        if (sessionId == null) {
            cineclube.currentSession ?: throw NotFoundException("Session not found")
        } else {
            cineclube.sessions.firstOrNull { it.id == sessionId } ?: throw NotFoundException("Session not found")
        }
}

/** The status a CineClube gets from its span. */
internal fun cineclubeStatusFor(startsOn: LocalDate, endsOn: LocalDate, today: LocalDate): CineClubeStatus = when {
    endsOn < today -> CineClubeStatus.FINISHED
    startsOn <= today -> CineClubeStatus.ACTIVE
    else -> CineClubeStatus.UPCOMING
}

/**
 * Puts the movie of a session on the user's Quero ver (F8.4), unless they already watched it.
 * A failure is logged and never fails the join.
 */
class CineClubeWatchlist(private val repository: CineClubeRepository, private val shelves: ShelfRepository) {
    private val log = LoggerFactory.getLogger(CineClubeWatchlist::class.java)

    fun add(userId: String, movieId: Long) {
        try {
            if (repository.hasWatched(userId, movieId)) return
            shelves.ensureSystemShelves(userId)
            val watchlist = watchlistOf(userId) ?: return
            if (watchlist.id !in shelves.shelfIdsWithMovie(userId, movieId)) shelves.addMovie(watchlist, movieId)
        } catch (e: Exception) {
            log.error("[CINECLUBE] Could not put movie $movieId on the Quero ver of $userId", e)
        }
    }

    private fun watchlistOf(userId: String) = generateSequence(1) { it + 1 }
        .map { shelves.getUserShelves(userId, Visibility.entries.toSet(), PageRequest(it, PageRequest.MAX_LIMIT)) }
        .takeWhile { it.results.isNotEmpty() }
        .flatMap { it.results }
        .firstOrNull { it.systemType == SystemShelfType.WATCHLIST }
}

class ListCineClubes(private val repository: CineClubeRepository, private val today: () -> LocalDate = ::today) {
    operator fun invoke(viewerId: String?, filter: CineClubeFilter, page: PageRequest): Page<CineClubeSummary> {
        if (filter.mine && viewerId == null) throw ForbiddenException("login_required", "Log in to see your CineClubes")
        return repository.list(viewerId, filter, page, today())
    }
}

/** The detail (F8.3) with where to watch the movie of the current session. */
class GetCineClube(private val access: CineClubeAccess, private val catalog: CineClubeCatalog) {
    suspend operator fun invoke(viewerId: String?, slug: String): CineClube {
        val cineclube = access.view(viewerId, slug)
        val tmdbId = cineclube.currentSession?.movie?.tmdbId ?: return cineclube
        val providers = runCatching { catalog.watchProviders(tmdbId) }.getOrDefault(emptyList())
        return cineclube.copy(watchProviders = providers)
    }
}

class CheckCineClubeSlug(private val repository: CineClubeRepository) {
    operator fun invoke(slug: String): SlugAvailability {
        val normalized = normalizedSlug(slug) ?: return SlugAvailability(slug, valid = false, available = false)
        return SlugAvailability(normalized, valid = true, available = !repository.slugTaken(normalized))
    }
}

/** Creates a CineClube (F8.5); the owner takes part in it from the start. */
class CreateCineClube(
    private val repository: CineClubeRepository,
    private val access: CineClubeAccess,
    private val shelves: ShelfRepository,
    private val hasher: PasswordHasher,
    private val watchlist: CineClubeWatchlist,
    private val activities: ActivityPublisher,
    private val today: () -> LocalDate = ::today
) {
    operator fun invoke(userId: String, input: CineClubeInput): CineClube {
        val now = today()
        val draft = cineclubeDraftOf(input, now, isNew = true) { movieOf(shelves, it) }
        val password = maratonaPassword(input.password)?.let(hasher::hash)
        if (repository.slugTaken(draft.slug)) throw ConflictException("slug_taken", "This address is already in use")
        val id = repository.create(userId, draft, cineclubeStatusFor(draft.startsOn, draft.endsOn, now), password)
        repository.join(id, userId)
        val cineclube = access.view(userId, draft.slug)
        cineclube.currentSession?.let { watchlist.add(userId, it.movie.id) }
        activities.cineclubeCreated(cineclube)
        return cineclube
    }
}

/**
 * Edits a CineClube (F8.5). Sessions that started stay as they are; the others change, and
 * new ones can be added after today (a cycle grows). A finished or cancelled one is read only.
 */
class UpdateCineClube(
    private val repository: CineClubeRepository,
    private val access: CineClubeAccess,
    private val shelves: ShelfRepository,
    private val hasher: PasswordHasher,
    private val today: () -> LocalDate = ::today
) {
    operator fun invoke(userId: String, slug: String, input: CineClubeInput): CineClube {
        val cineclube = access.owned(userId, slug)
        if (!cineclube.status.open) throw ConflictException("cineclube_closed", "This CineClube can no longer change")
        val now = today()
        val frozen = cineclube.sessions
            .filter { it.startsOn <= now }
            .map { SessionDraft(it.id, it.movie.id, it.startsOn, it.endsOn, it.debateOn) }
        val draft = cineclubeDraftOf(input.copy(slug = cineclube.slug), now, isNew = frozen.isEmpty(), frozen = frozen) {
            movieOf(shelves, it)
        }
        val password = when {
            input.removePassword -> PasswordChange.Remove
            input.password != null -> PasswordChange.Set(hasher.hash(maratonaPassword(input.password)!!))
            else -> PasswordChange.Keep
        }
        repository.update(cineclube.id, draft, frozen.mapNotNull { it.id }.toSet(), password)
        repository.setStatus(cineclube.id, cineclubeStatusFor(draft.startsOn, draft.endsOn, now))
        return access.view(userId, cineclube.slug)
    }
}

class CancelCineClube(private val repository: CineClubeRepository, private val access: CineClubeAccess) {
    operator fun invoke(userId: String, slug: String): CineClube {
        val cineclube = access.owned(userId, slug)
        if (!cineclube.status.open) throw ConflictException("cineclube_closed", "This CineClube already ended")
        repository.setStatus(cineclube.id, CineClubeStatus.CANCELLED)
        return access.view(userId, slug)
    }
}

/** Joins a CineClube (F8.4): the movie of the current session goes to Quero ver. */
class JoinCineClube(
    private val repository: CineClubeRepository,
    private val access: CineClubeAccess,
    private val hasher: PasswordHasher,
    private val watchlist: CineClubeWatchlist,
    private val activities: ActivityPublisher
) {
    operator fun invoke(userId: String, slug: String, password: String?): CineClube {
        val cineclube = access.get(slug)
        if (!cineclube.status.open) throw ConflictException("cineclube_closed", "This CineClube is not open")
        if (repository.participation(cineclube.id, userId) != null) {
            throw ConflictException("already_participating", "You already take part in this CineClube")
        }
        val hash = repository.passwordHash(cineclube.id)
        if (hash != null && (password == null || !hasher.verify(password, hash))) {
            throw ForbiddenException("wrong_password", "Wrong password")
        }
        repository.join(cineclube.id, userId)
        cineclube.currentSession?.let { watchlist.add(userId, it.movie.id) }
        activities.cineclubeJoined(userId, cineclube)
        return access.view(userId, slug)
    }
}

/** Leaves a CineClube. The movie stays on Quero ver: it is the user's now. */
class LeaveCineClube(
    private val repository: CineClubeRepository,
    private val access: CineClubeAccess,
    private val activities: ActivityPublisher
) {
    operator fun invoke(userId: String, slug: String) {
        val cineclube = access.get(slug)
        if (!cineclube.status.open) throw ConflictException("cineclube_closed", "This CineClube already ended")
        val participation = repository.participation(cineclube.id, userId)
            ?: throw ForbiddenException("not_participating", "Join the CineClube first")
        repository.leave(participation.id)
        activities.cineclubeLeft(userId, cineclube.id)
    }
}

/**
 * The participants' logs of a session (F8.3), newest first. The spoiler gate runs here, never
 * only in the app: a log with a spoiler or a comment comes without its comment and GIF to a
 * viewer who has not finished the movie, until the debate day.
 */
class GetCineClubeProgress(
    private val repository: CineClubeRepository,
    private val access: CineClubeAccess,
    private val today: () -> LocalDate = ::today
) {
    operator fun invoke(viewerId: String?, slug: String, sessionId: Long?, page: PageRequest): Page<CineClubeLog> {
        val cineclube = access.get(slug)
        val session = access.session(cineclube, sessionId)
        val logs = repository.sessionLogs(cineclube.id, session, viewerId, page)
        val open = today() >= session.debateOn || (viewerId != null && repository.hasWatched(viewerId, session.movie.id))
        if (open) return logs
        return logs.copy(results = logs.results.map { log ->
            val sensitive = log.hasSpoiler || log.comment != null || log.gifUrl != null
            if (!sensitive || log.user.id == viewerId) log
            else log.copy(comment = null, gifUrl = null, hidden = true, hiddenReason = SPOILER_REASON)
        })
    }
}

/** Participants of a session (F8.3), filtered by their progress. */
class GetCineClubeParticipants(private val repository: CineClubeRepository, private val access: CineClubeAccess) {
    operator fun invoke(
        slug: String,
        sessionId: Long?,
        progress: SessionProgress?,
        completedOnly: Boolean,
        page: PageRequest
    ): Page<CineClubeParticipant> {
        val cineclube = access.get(slug)
        val session = access.session(cineclube, sessionId)
        val wanted = if (completedOnly) SessionProgress.COMPLETED else progress
        val participants = repository.participants(cineclube.id, session, cineclube.sessions)
            .filter { wanted == null || it.progress == wanted }
        return Page.of(participants.drop(page.offset.toInt()).take(page.limit), page, participants.size)
    }
}

internal fun movieOf(shelves: ShelfRepository, session: SessionInput): Long =
    shelves.resolveMovieId(session.movieId, session.tmdbId, session.title) ?: throw NotFoundException("Movie not found")
