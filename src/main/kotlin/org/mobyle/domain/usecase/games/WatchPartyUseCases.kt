package org.mobyle.domain.usecase.games

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import org.mobyle.domain.model.ConflictException
import org.mobyle.domain.model.ForbiddenException
import org.mobyle.domain.model.MovieCard
import org.mobyle.domain.model.NotFoundException
import org.mobyle.domain.model.PartyMessage
import org.mobyle.domain.model.PosterRound
import org.mobyle.domain.model.SocialEvent
import org.mobyle.domain.model.UserSummary
import org.mobyle.domain.model.ValidationException
import org.mobyle.domain.model.WatchParty
import org.mobyle.domain.repository.MoviesRepository
import org.mobyle.domain.repository.SocialNotifier
import org.mobyle.domain.repository.UserProfileRepository
import org.mobyle.domain.repository.WatchRepository
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

private const val CODE_LENGTH = 6
private const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
private const val MAX_MEMBERS = 30
private const val MAX_MESSAGES = 100
private const val MESSAGE_MAX_LENGTH = 500
private const val TITLE_MAX_LENGTH = 100
private const val MIN_COUNTDOWN = 5
private const val MAX_COUNTDOWN = 3600
private val ROOM_TTL = 6.hours

@Serializable
data class CreatePartyInput(val title: String = "", val movieId: Long? = null)

/**
 * The rooms of the watch parties, in memory: they do not survive a restart and expire six
 * hours after they were created. Members get a `watch_party` event on the WebSocket on every
 * change (no push) and read the room again. One backend instance only; with more, the rooms
 * move to Redis (B10.4).
 */
class WatchParties(
    private val profiles: UserProfileRepository,
    private val movies: WatchRepository,
    private val notifier: SocialNotifier,
    private val clock: Clock = Clock.System,
    private val random: Random = Random.Default
) {
    private class Room(
        val code: String,
        val hostId: String,
        var title: String,
        val movie: MovieCard?,
        val createdAt: Instant,
        var startAt: Instant? = null,
        val members: MutableSet<String> = linkedSetOf(),
        val messages: ArrayDeque<StoredMessage> = ArrayDeque()
    )

    private data class StoredMessage(val id: Long, val userId: String, val body: String, val sentAt: Instant)

    private val rooms = ConcurrentHashMap<String, Room>()
    private val messageIds = AtomicLong()

    fun create(userId: String, input: CreatePartyInput): WatchParty {
        expire()
        val title = input.title.trim().ifEmpty { null }
        val movie = input.movieId?.let { id ->
            movies.getMovie(id)?.let { MovieCard(it.id, it.tmdbId, it.title, it.posterPath, null) }
                ?: throw NotFoundException("Movie not found")
        }
        val name = title ?: movie?.title ?: throw ValidationException("title", "A watch party needs a title or a movie")
        if (name.length > TITLE_MAX_LENGTH) throw ValidationException("title", "The title has at most $TITLE_MAX_LENGTH characters")
        val room = Room(newCode(), userId, name, movie, clock.now())
        room.members += userId
        rooms[room.code] = room
        return view(room, userId)
    }

    fun get(userId: String, code: String): WatchParty {
        val room = room(code)
        if (userId !in room.members) throw ForbiddenException("not_in_party", "Join the watch party first")
        return view(room, userId)
    }

    fun join(userId: String, code: String): WatchParty {
        val room = room(code)
        synchronized(room) {
            if (userId !in room.members) {
                if (room.members.size >= MAX_MEMBERS) throw ConflictException("party_full", "The watch party is full")
                room.members += userId
            }
        }
        broadcast(room, userId, "join")
        return view(room, userId)
    }

    fun leave(userId: String, code: String) {
        val room = room(code)
        synchronized(room) { room.members -= userId }
        if (room.members.isEmpty() || room.hostId == userId) {
            rooms.remove(room.code)
            broadcast(room, userId, "closed")
        } else {
            broadcast(room, userId, "leave")
        }
    }

    /** The host starts the countdown: everybody presses play at the same instant. */
    fun schedule(userId: String, code: String, inSeconds: Int): WatchParty {
        val room = room(code)
        if (room.hostId != userId) throw ForbiddenException("not_party_host", "Only the host starts the countdown")
        if (inSeconds !in MIN_COUNTDOWN..MAX_COUNTDOWN) {
            throw ValidationException("inSeconds", "The countdown takes $MIN_COUNTDOWN to $MAX_COUNTDOWN seconds")
        }
        room.startAt = clock.now() + inSeconds.seconds
        broadcast(room, userId, "start")
        return view(room, userId)
    }

    fun send(userId: String, code: String, body: String): WatchParty {
        val room = room(code)
        if (userId !in room.members) throw ForbiddenException("not_in_party", "Join the watch party first")
        val text = body.trim()
        if (text.isEmpty() || text.length > MESSAGE_MAX_LENGTH) {
            throw ValidationException("body", "A message has between 1 and $MESSAGE_MAX_LENGTH characters")
        }
        synchronized(room) {
            room.messages.addLast(StoredMessage(messageIds.incrementAndGet(), userId, text, clock.now()))
            while (room.messages.size > MAX_MESSAGES) room.messages.removeFirst()
        }
        broadcast(room, userId, "message")
        return view(room, userId)
    }

    private fun room(code: String): Room {
        expire()
        return rooms[code.uppercase()] ?: throw NotFoundException("Watch party not found")
    }

    private fun expire() {
        val limit = clock.now() - ROOM_TTL
        rooms.values.removeIf { it.createdAt < limit }
    }

    private fun newCode(): String {
        while (true) {
            val code = (1..CODE_LENGTH).map { CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)] }.joinToString("")
            if (!rooms.containsKey(code)) return code
        }
    }

    private fun broadcast(room: Room, fromId: String, event: String) {
        val members = synchronized(room) { room.members.toList() }
        (members + room.hostId).distinct().filter { it != fromId }.forEach {
            notifier.notify(it, SocialEvent.WatchPartyChanged(room.code, event))
        }
    }

    private fun view(room: Room, viewerId: String): WatchParty {
        val (members, messages) = synchronized(room) { room.members.toList() to room.messages.toList() }
        val users = (members + room.hostId + messages.map { it.userId }).distinct().associateWith(::summary)
        return WatchParty(
            code = room.code,
            host = users.getValue(room.hostId),
            title = room.title,
            movie = room.movie,
            startAt = room.startAt,
            members = members.map { users.getValue(it) },
            messages = messages.map { PartyMessage(it.id, users.getValue(it.userId), it.body, it.sentAt) },
            createdAt = room.createdAt,
            serverTime = clock.now(),
            self = room.hostId == viewerId
        )
    }

    private fun summary(userId: String): UserSummary {
        val profile = profiles.getProfile(userId)
        return UserSummary(userId, profile?.username ?: userId, profile?.handle, profile?.displayName, profile?.avatarUrl, profile?.accentColor)
    }
}

/**
 * A round of the poster game (F10.5): a movie from what is trending, with its poster, and
 * three other movies as wrong options, in random order. The answer goes along: the solo game
 * checks it in the app.
 */
class GetPosterRound(private val movies: MoviesRepository, private val random: Random = Random.Default) {
    suspend operator fun invoke(): PosterRound {
        val listing = movies.getTrendingMovies(random.nextInt(1, 6))
        val candidates = listing.movies.filter { !it.posterPath.isNullOrBlank() && it.id > 0 }.distinctBy { it.id }
        if (candidates.size < 4) throw NotFoundException("No movies for a round")
        val picked = candidates.shuffled(random).take(4).map {
            MovieCard(it.id, it.tmdbId, it.localTitle ?: it.title, it.posterPath, it.releaseDate?.take(4)?.toIntOrNull())
        }
        return PosterRound(picked.first(), picked.shuffled(random))
    }
}
