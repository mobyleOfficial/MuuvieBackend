package org.mobyle.domain.policy

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.mobyle.domain.model.vocabulary.ActivityType
import org.mobyle.domain.model.vocabulary.Visibility
import kotlin.time.Duration.Companion.minutes

/**
 * When activities become one (B6 dedupe). Sessions and the rating of one movie in a
 * [WINDOW] share an activity whose payload carries the latest `log` and `rating` blocks;
 * "watching now" of the same movie in the window replaces the previous one.
 */
object ActivityMergePolicy {
    val WINDOW = 10.minutes

    private val MOVIE_TYPES = setOf(ActivityType.WATCH_SESSION, ActivityType.FINISHED_MOVIE, ActivityType.RATING)

    /** Types a new activity of [type] merges into; empty when it always stands alone. */
    fun mergesWith(type: ActivityType): Set<ActivityType> = when (type) {
        in MOVIE_TYPES -> MOVIE_TYPES
        ActivityType.WATCHING_NOW -> setOf(ActivityType.WATCHING_NOW)
        else -> emptySet()
    }

    /** The type a movie activity takes from what its payload has: the session wins over the rating. */
    fun movieType(payload: JsonObject): ActivityType? {
        val log = payload["log"]?.jsonObject
        return when {
            log != null && log["completed"]?.jsonPrimitive?.booleanOrNull == true -> ActivityType.FINISHED_MOVIE
            log != null -> ActivityType.WATCH_SESSION
            payload["rating"] != null -> ActivityType.RATING
            else -> null
        }
    }

    /** A merged activity is only as visible as its most private part. */
    fun mostRestrictive(visibilities: Collection<Visibility>): Visibility =
        visibilities.maxByOrNull { RESTRICTION.indexOf(it) } ?: Visibility.DEFAULT

    private val RESTRICTION = listOf(Visibility.PUBLIC, Visibility.MUTUAL, Visibility.SECRET)
}
