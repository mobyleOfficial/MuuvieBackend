package org.mobyle.domain.usecase.feed

import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.mobyle.domain.model.ActivityDraft
import org.mobyle.domain.model.ActivityRecord
import org.mobyle.domain.model.ChallengeLink
import org.mobyle.domain.model.CineClube
import org.mobyle.domain.model.Locadora
import org.mobyle.domain.model.Maratona
import org.mobyle.domain.model.MaratonaChallenge
import org.mobyle.domain.model.MaratonaLevel
import org.mobyle.domain.model.MovieRating
import org.mobyle.domain.model.PetDefinition
import org.mobyle.domain.model.Shelf
import org.mobyle.domain.model.WatchLog
import org.mobyle.domain.model.vocabulary.ActivitySubject
import org.mobyle.domain.model.vocabulary.ActivityType
import org.mobyle.domain.model.vocabulary.ImportSource
import org.mobyle.domain.model.vocabulary.Visibility
import org.mobyle.domain.policy.ActivityMergePolicy
import org.mobyle.domain.repository.ActivityRepository
import org.mobyle.domain.repository.WatchableMovie
import org.slf4j.LoggerFactory

/**
 * Turns what users do into activities of the feed (F6.1). Called by the use cases after their
 * write succeeded; a failure here is logged and never fails the user's action.
 *
 * The payload is a snapshot for rendering. Edits refresh it and deletes take the activity
 * (or its block) away, so the feed never shows a session, shelf or locadora that is gone.
 */
class ActivityPublisher(private val repository: ActivityRepository, private val clock: Clock = Clock.System) {
    private val log = LoggerFactory.getLogger(ActivityPublisher::class.java)

    fun sessionLogged(userId: String, movie: WatchableMovie, watchLog: WatchLog) = safely {
        publishMovieBlock(userId, movie, LOG, logBlock(watchLog))
    }

    fun sessionChanged(userId: String, watchLog: WatchLog) = safely {
        movieActivities(userId, watchLog.movieId)
            .filter { it.blockId(LOG) == watchLog.id }
            .forEach { rewrite(it, it.payload.with(LOG, logBlock(watchLog))) }
    }

    fun sessionDeleted(userId: String, movieId: Long, logId: Long) = safely {
        movieActivities(userId, movieId)
            .filter { it.blockId(LOG) == logId }
            .forEach { rewrite(it, it.payload.without(LOG)) }
    }

    fun movieRated(userId: String, movie: WatchableMovie, rating: MovieRating) = safely {
        publishMovieBlock(userId, movie, RATING, ratingBlock(rating, repository.watchedVisibility(userId)))
    }

    fun ratingRemoved(userId: String, movieId: Long) = safely {
        movieActivities(userId, movieId)
            .filter { it.payload[RATING] != null }
            .forEach { rewrite(it, it.payload.without(RATING)) }
    }

    /** "Assistindo agora" is public, like the profile header that shows it (F2.6). */
    fun watchingNow(userId: String, movie: WatchableMovie) = safely {
        val payload = buildJsonObject { put(MOVIE, movieBlock(movie)) }
        val recent = repository.findRecent(
            userId, ActivitySubject.MOVIE, movie.id, setOf(ActivityType.WATCHING_NOW), clock.now() - ActivityMergePolicy.WINDOW
        )
        if (recent != null) {
            repository.update(recent.id, ActivityType.WATCHING_NOW, Visibility.PUBLIC, payload, touch = true)
        } else {
            repository.insert(
                ActivityDraft(userId, ActivityType.WATCHING_NOW, Visibility.PUBLIC, ActivitySubject.MOVIE, movie.id, movie.id, payload)
            )
        }
    }

    /** The system shelves exist from the start: only the user's own shelves are news. */
    fun shelfCreated(shelf: Shelf) = safely {
        if (shelf.systemType != null) return@safely
        repository.insert(
            ActivityDraft(
                shelf.ownerId, ActivityType.NEW_SHELF, shelf.visibility, ActivitySubject.SHELF, shelf.id, null,
                buildJsonObject { put(SHELF, shelfBlock(shelf)) }
            )
        )
    }

    /** The shelf became the featured one of the profile (F5.4). */
    fun shelfFeatured(shelf: Shelf) = safely {
        repository.insert(
            ActivityDraft(
                shelf.ownerId, ActivityType.FEATURED_SHELF, shelf.visibility, ActivitySubject.SHELF, shelf.id, null,
                buildJsonObject { put(SHELF, shelfBlock(shelf)) }
            )
        )
    }

    fun shelfChanged(shelf: Shelf) = safely {
        repository.findBySubject(shelf.ownerId, ActivitySubject.SHELF, shelf.id).forEach {
            repository.update(it.id, it.type, shelf.visibility, it.payload.with(SHELF, shelfBlock(shelf)), touch = false)
        }
    }

    fun shelfDeleted(userId: String, shelfId: Long) = safely {
        repository.findBySubject(userId, ActivitySubject.SHELF, shelfId).forEach { repository.delete(it.id) }
    }

    fun locadoraCreated(locadora: Locadora) = safely {
        repository.insert(
            ActivityDraft(
                locadora.ownerId, ActivityType.NEW_LOCADORA, locadora.visibility, ActivitySubject.LOCADORA, locadora.id, null,
                buildJsonObject { put(LOCADORA, locadoraBlock(locadora)) }
            )
        )
    }

    fun locadoraChanged(locadora: Locadora) = safely {
        repository.findBySubject(locadora.ownerId, ActivitySubject.LOCADORA, locadora.id).forEach {
            repository.update(it.id, it.type, locadora.visibility, it.payload.with(LOCADORA, locadoraBlock(locadora)), touch = false)
        }
    }

    fun locadoraDeleted(userId: String, locadoraId: Long) = safely {
        repository.findBySubject(userId, ActivitySubject.LOCADORA, locadoraId).forEach { repository.delete(it.id) }
    }

    /** One activity per import, as visible as the Já assisti it filled. */
    fun moviesImported(userId: String, jobId: Long, source: ImportSource, watched: Int, watchlist: Int) = safely {
        if (watched + watchlist <= 0) return@safely
        repository.insert(
            ActivityDraft(
                userId, ActivityType.IMPORTED_MOVIES, repository.watchedVisibility(userId), ActivitySubject.IMPORT, jobId, null,
                buildJsonObject {
                    putJsonObject(IMPORT) {
                        put("source", source.wireValue)
                        put("watched", watched)
                        put("watchlist", watchlist)
                    }
                }
            )
        )
    }

    // ── Maratonas (B7) ──────────────────────────────────────────────────────
    // A maratona is public, and so is what people do in it.

    fun maratonaCreated(maratona: Maratona) = safely {
        publishMaratona(maratona.owner.id, ActivityType.CREATED_MARATONA, maratona, buildJsonObject {})
    }

    fun maratonaJoined(userId: String, maratona: Maratona) = safely {
        publishMaratona(userId, ActivityType.JOINED_MARATONA, maratona, buildJsonObject {})
    }

    /** Leaving takes back what the user did in the maratona. */
    fun maratonaLeft(userId: String, maratonaId: Long) = safely {
        repository.findBySubject(userId, ActivitySubject.MARATONA, maratonaId)
            .filter { it.type != ActivityType.CREATED_MARATONA }
            .forEach { repository.delete(it.id) }
    }

    fun challengeCompleted(userId: String, maratona: Maratona, challenge: MaratonaChallenge, link: ChallengeLink) = safely {
        publishMaratona(
            userId, ActivityType.FINISHED_CHALLENGE, maratona,
            buildJsonObject {
                putJsonObject(CHALLENGE) {
                    put("id", challenge.id)
                    put("description", challenge.description)
                }
                putJsonObject(MOVIE) {
                    put("id", link.movieId)
                    put("tmdbId", link.tmdbId)
                    put("title", link.title)
                    put("posterPath", link.posterPath)
                }
            },
            movieId = link.movieId
        )
    }

    /** The challenge has no movie left: its "concluiu desafio" goes away. */
    fun challengeUndone(userId: String, maratonaId: Long, challengeId: Long) = safely {
        repository.findBySubject(userId, ActivitySubject.MARATONA, maratonaId)
            .filter { it.type == ActivityType.FINISHED_CHALLENGE && it.blockId(CHALLENGE) == challengeId }
            .forEach { repository.delete(it.id) }
    }

    fun maratonaFinished(userId: String, maratona: Maratona, level: MaratonaLevel, completed: Int) = safely {
        publishMaratona(
            userId, ActivityType.FINISHED_MARATONA, maratona,
            buildJsonObject {
                putJsonObject(LEVEL) {
                    put("id", level.id)
                    put("name", level.name)
                    put("completed", completed)
                    put("total", maratona.totalChallenges)
                }
            }
        )
    }

    private fun publishMaratona(userId: String, type: ActivityType, maratona: Maratona, extra: JsonObject, movieId: Long? = null) {
        val payload = JsonObject(mapOf(MARATONA to maratonaBlock(maratona)) + extra)
        repository.insert(ActivityDraft(userId, type, Visibility.PUBLIC, ActivitySubject.MARATONA, maratona.id, movieId, payload))
    }

    // ── Bichinho (B10) ──────────────────────────────────────────────────────

    /** A new bichinho in the shelter: public, like the profile that shows it. */
    fun petUnlocked(userId: String, petId: Long, pet: PetDefinition) = safely {
        repository.insert(
            ActivityDraft(
                userId, ActivityType.UNLOCKED_BICHINHO, Visibility.PUBLIC, ActivitySubject.BICHINHO, petId, null,
                buildJsonObject {
                    putJsonObject(BICHINHO) {
                        put("slug", pet.slug)
                        put("rarity", pet.rarity.wireValue)
                    }
                }
            )
        )
    }

    // ── CineClubes (B8) ─────────────────────────────────────────────────────
    // A CineClube is public, and so is joining it.

    fun cineclubeCreated(cineclube: CineClube) = safely {
        publishCineClube(cineclube.owner.id, ActivityType.CREATED_CINECLUBE, cineclube)
    }

    fun cineclubeJoined(userId: String, cineclube: CineClube) = safely {
        publishCineClube(userId, ActivityType.JOINED_CINECLUBE, cineclube)
    }

    /** Leaving takes the "entrou no CineClube" back. */
    fun cineclubeLeft(userId: String, cineclubeId: Long) = safely {
        repository.findBySubject(userId, ActivitySubject.CINECLUBE, cineclubeId)
            .filter { it.type == ActivityType.JOINED_CINECLUBE }
            .forEach { repository.delete(it.id) }
    }

    private fun publishCineClube(userId: String, type: ActivityType, cineclube: CineClube) {
        val movie = cineclube.currentSession?.movie
        val payload = buildJsonObject {
            put(CINECLUBE, cineclubeBlock(cineclube))
            if (movie != null) {
                putJsonObject(MOVIE) {
                    put("id", movie.id)
                    put("tmdbId", movie.tmdbId)
                    put("title", movie.title)
                    put("posterPath", movie.posterPath)
                }
            }
        }
        repository.insert(ActivityDraft(userId, type, Visibility.PUBLIC, ActivitySubject.CINECLUBE, cineclube.id, movie?.id, payload))
    }

    // ── Movie activities ────────────────────────────────────────────────────

    private fun publishMovieBlock(userId: String, movie: WatchableMovie, key: String, block: JsonObject) {
        val recent = repository.findRecent(
            userId, ActivitySubject.MOVIE, movie.id, ActivityMergePolicy.mergesWith(ActivityType.WATCH_SESSION),
            clock.now() - ActivityMergePolicy.WINDOW
        )
        if (recent != null) {
            rewrite(recent, recent.payload.with(MOVIE, movieBlock(movie)).with(key, block), touch = true)
            return
        }
        val payload = buildJsonObject {
            put(MOVIE, movieBlock(movie))
            put(key, block)
        }
        repository.insert(
            ActivityDraft(
                userId, ActivityMergePolicy.movieType(payload)!!, movieVisibility(payload, Visibility.DEFAULT),
                ActivitySubject.MOVIE, movie.id, movie.id, payload
            )
        )
    }

    private fun movieActivities(userId: String, movieId: Long): List<ActivityRecord> =
        repository.findBySubject(userId, ActivitySubject.MOVIE, movieId)
            .filter { it.type != ActivityType.WATCHING_NOW }

    /** Saves a movie activity with the type and visibility its blocks give; one with no block left is deleted. */
    private fun rewrite(record: ActivityRecord, payload: JsonObject, touch: Boolean = false) {
        val type = ActivityMergePolicy.movieType(payload)
        if (type == null) {
            repository.delete(record.id)
        } else {
            repository.update(record.id, type, movieVisibility(payload, record.visibility), payload, touch)
        }
    }

    /** Blocks carry their own visibility; old (backfilled) ones fall back to the activity's. */
    private fun movieVisibility(payload: JsonObject, fallback: Visibility): Visibility =
        ActivityMergePolicy.mostRestrictive(
            listOf(LOG, RATING).mapNotNull { key ->
                payload[key]?.jsonObject?.let { block ->
                    block["visibility"]?.jsonPrimitive?.content?.let(Visibility::fromWireValue) ?: fallback
                }
            }
        )

    private fun ActivityRecord.blockId(key: String): Long? =
        payload[key]?.jsonObject?.get("id")?.jsonPrimitive?.longOrNull

    private fun safely(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            log.error("[FEED] Could not publish the activity", e)
        }
    }

    private fun JsonObject.with(key: String, value: JsonObject) = JsonObject(this + (key to value))

    private fun JsonObject.without(key: String) = JsonObject(this - key)

    private companion object {
        const val MOVIE = "movie"
        const val LOG = "log"
        const val RATING = "rating"
        const val SHELF = "shelf"
        const val LOCADORA = "locadora"
        const val IMPORT = "import"
        const val MARATONA = "maratona"
        const val CHALLENGE = "challenge"
        const val LEVEL = "level"
        const val CINECLUBE = "cineclube"
        const val BICHINHO = "bichinho"

        fun movieBlock(movie: WatchableMovie) = buildJsonObject {
            put("id", movie.id)
            put("tmdbId", movie.tmdbId)
            put("title", movie.title)
            put("posterPath", movie.posterPath)
        }

        fun logBlock(log: WatchLog) = buildJsonObject {
            put("id", log.id)
            put("watchedOn", log.watchedOn.toString())
            put("cycle", log.cycle)
            put("mode", log.mode.wireValue)
            put("minutes", log.minutesWatched)
            put("percent", log.percent)
            put("completed", log.completed)
            put("venue", log.venue?.wireValue)
            put("providerName", log.providerName)
            putJsonArray("emotions") { log.emotions.forEach { add(JsonPrimitive(it.wireValue)) } }
            put("gifUrl", log.gif?.url)
            put("comment", log.comment)
            put("hasSpoiler", log.hasSpoiler)
            put("visibility", log.visibility.wireValue)
        }

        fun ratingBlock(rating: MovieRating, visibility: Visibility) = buildJsonObject {
            put("id", rating.id)
            put("rating", rating.rating.wireValue)
            put("review", rating.review)
            put("hasSpoiler", rating.hasSpoiler)
            put("visibility", visibility.wireValue)
        }

        fun shelfBlock(shelf: Shelf) = buildJsonObject {
            put("id", shelf.id)
            put("name", shelf.name)
            put("color", shelf.color)
            put("totalMovies", shelf.totalMovies)
            putJsonArray("posterPaths") { shelf.posterPaths.take(4).forEach { add(JsonPrimitive(it)) } }
        }

        fun maratonaBlock(maratona: Maratona) = buildJsonObject {
            put("id", maratona.id)
            put("slug", maratona.slug)
            put("title", maratona.title)
            put("logoUrl", maratona.logoUrl)
            put("accentColor", maratona.accentColor)
        }

        fun cineclubeBlock(cineclube: CineClube) = buildJsonObject {
            put("id", cineclube.id)
            put("slug", cineclube.slug)
            put("title", cineclube.title)
            put("accentColor", cineclube.accentColor)
            put("isCycle", cineclube.isCycle)
        }

        fun locadoraBlock(locadora: Locadora) = buildJsonObject {
            put("id", locadora.id)
            put("name", locadora.name)
            put("totalShelves", locadora.totalShelves)
            put("totalMovies", locadora.totalMovies)
            putJsonArray("posterPaths") { locadora.posterPaths.take(4).forEach { add(JsonPrimitive(it)) } }
        }
    }
}
