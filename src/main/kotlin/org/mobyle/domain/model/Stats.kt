package org.mobyle.domain.model

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import org.mobyle.domain.model.vocabulary.Emotion
import org.mobyle.domain.model.vocabulary.Rating
import org.mobyle.domain.model.vocabulary.WatchVenue

/**
 * A year of a user's sessions (B9.1), counting only the sessions the viewer can see.
 * [movies] are the distinct movies finished in the year, [minutes] everything watched,
 * finished or not. Each breakdown is sorted from the largest bucket.
 */
@Serializable
data class YearStats(
    val year: Int,
    val movies: Int,
    val sessions: Int,
    val minutes: Int,
    val rewatches: Int,
    val months: List<MonthStat>,
    val genres: List<StatBucket>,
    val decades: List<StatBucket>,
    val countries: List<StatBucket>,
    val languages: List<StatBucket>,
    val venues: List<StatBucket>,
    val directors: List<StatBucket>,
    val emotions: List<StatBucket>,
    // Finished movies by rating label, in the order of the vocabulary (B13.4).
    val ratings: List<StatBucket>
)

/** One slice of a breakdown: [key] is stable (a TMDB id, "1970", "BR", "cinema"), [label] for display. */
@Serializable
data class StatBucket(val key: String, val label: String? = null, val movies: Int, val minutes: Int = 0)

@Serializable
data class MonthStat(val month: Int, val movies: Int, val minutes: Int, val sessions: Int)

/** A day of the calendar heatmap. */
@Serializable
data class CalendarDay(val date: LocalDate, val sessions: Int, val minutes: Int)

@Serializable
data class StatsYears(val years: List<Int>)

/** A person in a top list of the retrospective. */
@Serializable
data class TopPerson(val tmdbId: Int, val name: String, val profilePath: String?, val movies: Int)

/** The year in stories (B9.2). Only the owner reads it. */
@Serializable
data class Retrospective(
    val year: Int,
    val movies: Int,
    val sessions: Int,
    val minutes: Int,
    val rewatches: Int,
    val topGenres: List<StatBucket>,
    val topDirectors: List<TopPerson>,
    val topActors: List<TopPerson>,
    val emotion: Emotion?,
    val favorite: MovieCard?,
    val favoriteRating: Rating?,
    val maratonasFinished: Int,
    val cineclubes: Int,
    val bestMonth: MonthStat?,
    val goal: YearlyGoal?
)

/** A yearly goal (B9.3) with its progress: movies finished in the year. */
@Serializable
data class YearlyGoal(val year: Int, val targetMovies: Int, val watched: Int) {
    val percent: Int get() = if (targetMovies <= 0) 0 else (watched * 100 / targetMovies).coerceAtMost(100)
    val reached: Boolean get() = watched >= targetMovies
}

// ── Facts the calculator reads ──────────────────────────────────────────────

/** A session as the statistics need it. */
data class LogFact(
    val movieId: Long,
    val watchedOn: LocalDate,
    val minutes: Int?,
    val completed: Boolean,
    val cycle: Int,
    val venue: WatchVenue?,
    val emotions: List<Emotion>
)

/** What the breakdowns read from a movie. */
data class MovieStatFacts(
    val movieId: Long,
    val tmdbId: Int?,
    val title: String,
    val posterPath: String?,
    val year: Int?,
    val genres: List<Pair<Int, String>>,
    val countries: Set<String>,
    val language: String?,
    val directors: List<PersonFact>,
    val actors: List<PersonFact>
)

data class PersonFact(val tmdbId: Int, val name: String, val profilePath: String?)

/** The user's rating of a movie, when the viewer may see it. */
data class RatingFact(val rating: Rating)
