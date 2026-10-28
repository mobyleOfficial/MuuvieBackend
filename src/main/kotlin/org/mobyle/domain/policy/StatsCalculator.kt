package org.mobyle.domain.policy

import org.mobyle.domain.model.CalendarDay
import org.mobyle.domain.model.LogFact
import org.mobyle.domain.model.MonthStat
import org.mobyle.domain.model.MovieStatFacts
import org.mobyle.domain.model.PersonFact
import org.mobyle.domain.model.RatingFact
import org.mobyle.domain.model.StatBucket
import org.mobyle.domain.model.TopPerson
import org.mobyle.domain.model.YearStats

/**
 * Turns a year of sessions into the numbers of the charts (B9.1). A movie counts once in
 * each breakdown, from its sessions finished in the year; minutes count every session.
 * Movie facts not enriched yet simply leave the movie out of that breakdown.
 */
object StatsCalculator {
    private const val TOP_PEOPLE = 20

    fun year(
        year: Int,
        logs: List<LogFact>,
        movies: Map<Long, MovieStatFacts>,
        ratings: Map<Long, RatingFact>
    ): YearStats {
        val finished = logs.filter { it.completed }.map { it.movieId }.distinct()
        val minutesByMovie = logs.groupBy { it.movieId }.mapValues { (_, list) -> list.sumOf { it.minutes ?: 0 } }
        val facts = finished.mapNotNull { movies[it] }

        fun buckets(keys: (MovieStatFacts) -> List<Pair<String, String?>>): List<StatBucket> =
            facts.flatMap { movie -> keys(movie).distinctBy { it.first }.map { it to movie.movieId } }
                .groupBy({ it.first.first }, { it })
                .map { (key, entries) ->
                    StatBucket(
                        key = key,
                        label = entries.first().first.second,
                        movies = entries.size,
                        minutes = entries.sumOf { minutesByMovie[it.second] ?: 0 }
                    )
                }
                .sortedWith(compareByDescending<StatBucket> { it.movies }.thenByDescending { it.minutes }.thenBy { it.key })

        val months = (1..12).map { month ->
            val inMonth = logs.filter { it.watchedOn.monthNumber == month }
            MonthStat(
                month = month,
                movies = inMonth.filter { it.completed }.map { it.movieId }.distinct().size,
                minutes = inMonth.sumOf { it.minutes ?: 0 },
                sessions = inMonth.size
            )
        }
        val emotions = logs.flatMap { it.emotions }
            .groupingBy { it }
            .eachCount()
            .map { (emotion, count) -> StatBucket(emotion.wireValue, null, count) }
            .sortedWith(compareByDescending<StatBucket> { it.movies }.thenBy { it.key })
        val rated = finished.mapNotNull { ratings[it] }

        return YearStats(
            year = year,
            movies = finished.size,
            sessions = logs.size,
            minutes = logs.sumOf { it.minutes ?: 0 },
            rewatches = logs.filter { it.completed && it.cycle > 1 }.map { it.movieId to it.cycle }.distinct().size,
            months = months,
            genres = buckets { movie -> movie.genres.map { (id, name) -> id.toString() to name } },
            decades = buckets { movie -> listOfNotNull(movie.year?.let { (it / 10 * 10).toString() to null }) },
            countries = buckets { movie -> movie.countries.map { it to null } },
            languages = buckets { movie -> listOfNotNull(movie.language?.let { it to null }) },
            venues = logs.filter { it.venue != null }
                .groupBy { it.venue!! }
                .map { (venue, list) ->
                    StatBucket(venue.wireValue, null, list.filter { it.completed }.map { it.movieId }.distinct().size, list.sumOf { it.minutes ?: 0 })
                }
                .sortedWith(compareByDescending<StatBucket> { it.movies }.thenByDescending { it.minutes }),
            directors = buckets { movie -> movie.directors.map { it.tmdbId.toString() to it.name } }.take(TOP_PEOPLE),
            emotions = emotions,
            ratings = rated.groupingBy { it.rating }.eachCount()
                .entries.sortedBy { it.key.ordinal }
                .map { (rating, count) -> StatBucket(rating.wireValue, null, count) }
        )
    }

    fun calendar(logs: List<LogFact>): List<CalendarDay> =
        logs.groupBy { it.watchedOn }
            .map { (day, list) -> CalendarDay(day, list.size, list.sumOf { it.minutes ?: 0 }) }
            .sortedBy { it.date }

    /** People of the finished movies, by how many of them they are in. */
    fun topPeople(finished: List<MovieStatFacts>, people: (MovieStatFacts) -> List<PersonFact>, limit: Int): List<TopPerson> =
        finished.flatMap { movie -> people(movie).distinctBy { it.tmdbId } }
            .groupBy { it.tmdbId }
            .map { (id, list) -> TopPerson(id, list.first().name, list.first().profilePath, list.size) }
            .sortedWith(compareByDescending<TopPerson> { it.movies }.thenBy { it.name })
            .take(limit)
}
