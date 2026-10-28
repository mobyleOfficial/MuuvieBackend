package org.mobyle.domain.usecase.maratonas

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import org.mobyle.domain.model.Activity
import org.mobyle.domain.model.ConflictException
import org.mobyle.domain.model.Cursor
import org.mobyle.domain.model.FeedCursor
import org.mobyle.domain.model.ForbiddenException
import org.mobyle.domain.model.Maratona
import org.mobyle.domain.model.MaratonaCategory
import org.mobyle.domain.model.MaratonaChallenge
import org.mobyle.domain.model.MaratonaFilter
import org.mobyle.domain.model.MaratonaSummary
import org.mobyle.domain.model.Movie
import org.mobyle.domain.model.MyParticipation
import org.mobyle.domain.model.NotFoundException
import org.mobyle.domain.model.Page
import org.mobyle.domain.model.PageRequest
import org.mobyle.domain.model.PasswordChange
import org.mobyle.domain.model.RankingEntry
import org.mobyle.domain.model.ShelfDraft
import org.mobyle.domain.model.TeamRankingEntry
import org.mobyle.domain.model.ValidationException
import org.mobyle.domain.model.vocabulary.ActivitySubject
import org.mobyle.domain.model.vocabulary.ChallengeLinkSource
import org.mobyle.domain.model.vocabulary.MaratonaStatus
import org.mobyle.domain.model.vocabulary.Visibility
import org.mobyle.domain.repository.ActivityRepository
import org.mobyle.domain.repository.MaratonaCatalog
import org.mobyle.domain.repository.MaratonaRepository
import org.mobyle.domain.repository.PasswordHasher
import org.mobyle.domain.repository.ShelfRepository
import org.mobyle.domain.usecase.feed.ActivityPublisher
import org.mobyle.domain.usecase.watch.today

private const val QUEUE_NAME_MAX_LENGTH = 150
private const val MAX_CO_OWNERS = 10
private const val PROGRESS_PAGE_SIZE = 20

/** Loads maratonas and checks who may change them. */
class MaratonaAccess(private val repository: MaratonaRepository) {
    fun get(slug: String): Maratona = repository.get(slug.lowercase()) ?: throw NotFoundException("Maratona not found")

    /** The maratona as [viewerId] sees it: their participation and whether they edit it. */
    fun view(viewerId: String?, slug: String): Maratona {
        val maratona = get(slug)
        if (viewerId == null) return maratona
        val participation = repository.participation(maratona.id, viewerId)
        return maratona.copy(
            canEdit = maratona.isEditor(viewerId),
            me = participation?.let {
                MyParticipation(it.levelId, it.teamId, it.queueShelfId, repository.completedChallenges(it.id), it.joinedAt)
            }
        )
    }

    /** Owner or co-owner. */
    fun editable(userId: String, slug: String): Maratona {
        val maratona = get(slug)
        if (!maratona.isEditor(userId)) throw ForbiddenException("not_maratona_editor", "Only the owners can change this maratona")
        return maratona
    }

    /** Only the creator. */
    fun owned(userId: String, slug: String): Maratona {
        val maratona = get(slug)
        if (!maratona.isOwner(userId)) throw ForbiddenException("not_maratona_owner", "Only the creator can do this")
        return maratona
    }

    fun participation(userId: String, maratona: Maratona) =
        repository.participation(maratona.id, userId)
            ?: throw ForbiddenException("not_participating", "Join the maratona first")
}

/** The status a maratona gets from its period. */
internal fun statusFor(startsOn: LocalDate, endsOn: LocalDate, today: LocalDate): MaratonaStatus = when {
    endsOn < today -> MaratonaStatus.FINISHED
    startsOn <= today -> MaratonaStatus.ACTIVE
    else -> MaratonaStatus.UPCOMING
}

class ListMaratonas(private val repository: MaratonaRepository) {
    operator fun invoke(viewerId: String?, filter: MaratonaFilter, page: PageRequest): Page<MaratonaSummary> {
        if (filter.mine && viewerId == null) throw ForbiddenException("login_required", "Log in to see your maratonas")
        return repository.list(viewerId, filter, page)
    }
}

class GetMaratona(private val access: MaratonaAccess) {
    operator fun invoke(viewerId: String?, slug: String): Maratona = access.view(viewerId, slug)
}

@Serializable
data class SlugAvailability(val slug: String, val valid: Boolean, val available: Boolean)

class CheckMaratonaSlug(private val repository: MaratonaRepository) {
    operator fun invoke(slug: String): SlugAvailability {
        val normalized = normalizedSlug(slug) ?: return SlugAvailability(slug, valid = false, available = false)
        return SlugAvailability(normalized, valid = true, available = !repository.slugTaken(normalized))
    }
}

class CreateMaratona(
    private val repository: MaratonaRepository,
    private val access: MaratonaAccess,
    private val hasher: PasswordHasher,
    private val activities: ActivityPublisher,
    private val today: () -> LocalDate = ::today
) {
    operator fun invoke(userId: String, input: MaratonaInput, templateId: String? = null): Maratona {
        val now = today()
        val draft = maratonaDraftOf(input, templateId, now, isNew = true)
        val password = maratonaPassword(input.password)?.let(hasher::hash)
        if (repository.slugTaken(draft.slug)) throw ConflictException("slug_taken", "This address is already in use")
        repository.create(userId, draft, statusFor(draft.startsOn, draft.endsOn, now), password)
        val maratona = access.view(userId, draft.slug)
        activities.maratonaCreated(maratona)
        return maratona
    }
}

/**
 * Edits a maratona (F7.7). Before the start everything changes but the address; after it,
 * only the texts, logo, color, link and prizes. A finished or cancelled one is read only.
 */
class UpdateMaratona(
    private val repository: MaratonaRepository,
    private val access: MaratonaAccess,
    private val hasher: PasswordHasher,
    private val today: () -> LocalDate = ::today
) {
    operator fun invoke(userId: String, slug: String, input: MaratonaInput): Maratona {
        val maratona = access.editable(userId, slug)
        if (!maratona.status.open) throw ConflictException("maratona_closed", "This maratona can no longer change")
        val started = maratona.status == MaratonaStatus.ACTIVE
        val now = today()
        val sent = input.copy(slug = maratona.slug).let {
            // After the start the period stays; the draft still has to validate.
            if (started) it.copy(startsOn = maratona.startsOn.toString(), endsOn = maratona.endsOn.toString()) else it
        }
        val draft = maratonaDraftOf(sent, maratona.templateId, now, isNew = !started)
        val password = when {
            input.removePassword -> PasswordChange.Remove
            input.password != null -> PasswordChange.Set(hasher.hash(maratonaPassword(input.password)!!))
            else -> PasswordChange.Keep
        }
        repository.update(maratona.id, draft, structure = !started, password = password)
        if (!started) repository.setStatus(maratona.id, statusFor(draft.startsOn, draft.endsOn, now))
        return access.view(userId, maratona.slug)
    }
}

class CancelMaratona(private val repository: MaratonaRepository, private val access: MaratonaAccess) {
    operator fun invoke(userId: String, slug: String): Maratona {
        val maratona = access.owned(userId, slug)
        if (!maratona.status.open) throw ConflictException("maratona_closed", "This maratona already ended")
        repository.setStatus(maratona.id, MaratonaStatus.CANCELLED)
        return access.view(userId, slug)
    }
}

class SetMaratonaCoOwners(
    private val repository: MaratonaRepository,
    private val access: MaratonaAccess,
    private val shelves: ShelfRepository
) {
    operator fun invoke(userId: String, slug: String, userIds: List<String>): Maratona {
        val maratona = access.owned(userId, slug)
        val coOwners = userIds.distinct().filter { it != userId }
        if (coOwners.size > MAX_CO_OWNERS) throw ValidationException("userIds", "A maratona has at most $MAX_CO_OWNERS co-owners")
        coOwners.forEach { if (!shelves.userExists(it)) throw ValidationException("userIds", "Unknown user $it") }
        repository.setCoOwners(maratona.id, coOwners)
        return access.view(userId, slug)
    }
}

/**
 * Joins a maratona (F7.4) at a level, optionally in a team, and creates the "Fila" shelf.
 * Without a level, the lowest one; a password-protected maratona asks for it.
 */
class JoinMaratona(
    private val repository: MaratonaRepository,
    private val access: MaratonaAccess,
    private val shelves: ShelfRepository,
    private val hasher: PasswordHasher,
    private val activities: ActivityPublisher
) {
    operator fun invoke(userId: String, slug: String, levelId: Long?, teamId: Long?, password: String?): Maratona {
        val maratona = access.get(slug)
        if (!maratona.status.open) throw ConflictException("maratona_closed", "This maratona is not open")
        if (repository.participation(maratona.id, userId) != null) {
            throw ConflictException("already_participating", "You already take part in this maratona")
        }
        val hash = repository.passwordHash(maratona.id)
        if (hash != null && (password == null || !hasher.verify(password, hash))) {
            throw ForbiddenException("wrong_password", "Wrong password")
        }
        val level = if (levelId != null) {
            maratona.levels.firstOrNull { it.id == levelId } ?: throw ValidationException("levelId", "Unknown level")
        } else {
            maratona.levels.minBy { it.minChallenges }
        }
        if (teamId != null && maratona.teams.none { it.id == teamId }) throw ValidationException("teamId", "Unknown team")
        val queue = shelves.createShelf(
            userId,
            ShelfDraft("Fila: ${maratona.title}".take(QUEUE_NAME_MAX_LENGTH), null, maratona.accentColor, Visibility.PUBLIC)
        )
        repository.join(maratona.id, userId, level.id, teamId, queue.id)
        activities.maratonaJoined(userId, maratona)
        return access.view(userId, slug)
    }
}

/** Leaves a maratona: the "Fila" shelf and the challenge links go with it. */
class LeaveMaratona(
    private val repository: MaratonaRepository,
    private val access: MaratonaAccess,
    private val shelves: ShelfRepository,
    private val activities: ActivityPublisher
) {
    operator fun invoke(userId: String, slug: String) {
        val maratona = access.get(slug)
        if (maratona.status == MaratonaStatus.FINISHED || maratona.status == MaratonaStatus.CANCELLED) {
            throw ConflictException("maratona_closed", "This maratona already ended")
        }
        val participation = access.participation(userId, maratona)
        repository.leave(participation.id)
        participation.queueShelfId?.let { shelfId ->
            if (shelves.getShelf(shelfId)?.ownerId == userId) shelves.deleteShelf(shelfId)
        }
        activities.maratonaLeft(userId, maratona.id)
    }
}

/** Categories and challenges, with what the viewer completed and the movies they tied (F7.3). */
class GetMaratonaChallenges(private val repository: MaratonaRepository, private val access: MaratonaAccess) {
    operator fun invoke(viewerId: String?, slug: String, teamOnly: Boolean?): List<MaratonaCategory> {
        val maratona = access.get(slug)
        val links = viewerId?.let { repository.participation(maratona.id, it) }
            ?.let { repository.links(it.id) }
            .orEmpty()
            .groupBy { it.challengeId }
        return maratona.categories
            .filter { teamOnly == null || it.teamOnly == teamOnly }
            .map { category ->
                category.copy(challenges = category.challenges.map { challenge ->
                    val mine = links[challenge.id].orEmpty()
                    challenge.copy(completed = mine.isNotEmpty(), links = mine)
                })
            }
    }
}

/** Movies that fit a challenge's criteria ("Descoberta", F7.3). A free challenge has none. */
class GetChallengeSuggestions(private val access: MaratonaAccess, private val catalog: MaratonaCatalog) {
    operator fun invoke(slug: String, challengeId: Long, page: Int): Page<Movie> {
        val challenge = access.get(slug).challenges.firstOrNull { it.id == challengeId }
            ?: throw NotFoundException("Challenge not found")
        val criteria = challenge.criteria ?: return Page(emptyList(), page, 0, 0, false)
        return catalog.suggestions(criteria, page)
    }
}

/**
 * Ties a movie to a challenge by hand, or takes it back (F7.5). Only while the maratona runs.
 * The first movie completes the challenge; removing the last one undoes it.
 */
class SetChallengeLink(
    private val repository: MaratonaRepository,
    private val access: MaratonaAccess,
    private val shelves: ShelfRepository,
    private val activities: ActivityPublisher
) {
    operator fun invoke(userId: String, slug: String, challengeId: Long, movieId: Long, linked: Boolean): MaratonaChallenge {
        val maratona = access.get(slug)
        if (maratona.status != MaratonaStatus.ACTIVE) throw ConflictException("maratona_not_active", "This maratona is not running")
        val participation = access.participation(userId, maratona)
        val challenge = maratona.challenges.firstOrNull { it.id == challengeId } ?: throw NotFoundException("Challenge not found")
        val before = repository.links(participation.id).filter { it.challengeId == challengeId }
        if (linked) {
            shelves.resolveMovieId(movieId, null, null) ?: throw NotFoundException("Movie not found")
            if (repository.addLink(participation.id, challengeId, movieId, null, ChallengeLinkSource.MANUAL) && before.isEmpty()) {
                repository.links(participation.id)
                    .firstOrNull { it.challengeId == challengeId && it.movieId == movieId }
                    ?.let { activities.challengeCompleted(userId, maratona, challenge, it) }
            }
        } else if (repository.removeLink(participation.id, challengeId, movieId) && before.size == 1) {
            activities.challengeUndone(userId, maratona.id, challengeId)
        }
        val after = repository.links(participation.id).filter { it.challengeId == challengeId }
        return challenge.copy(completed = after.isNotEmpty(), links = after)
    }
}

class GetMaratonaRanking(private val repository: MaratonaRepository, private val access: MaratonaAccess) {
    fun users(slug: String, page: PageRequest): Page<RankingEntry> = repository.ranking(access.get(slug).id, page)

    fun teams(slug: String): List<TeamRankingEntry> = repository.teamRanking(access.get(slug).id)
}

/** What people did in the maratona, newest first (F7.6). */
class GetMaratonaProgress(private val access: MaratonaAccess, private val activities: ActivityRepository) {
    operator fun invoke(viewerId: String?, slug: String, cursor: String?): Cursor<Activity> {
        val maratona = access.get(slug)
        return activities.subjectActivities(
            viewerId, ActivitySubject.MARATONA, maratona.id, FeedCursor.decode(cursor), PROGRESS_PAGE_SIZE
        )
    }
}
