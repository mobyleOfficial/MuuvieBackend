package org.mobyle.domain.policy

import kotlinx.datetime.LocalDate
import org.mobyle.domain.model.LogFact
import org.mobyle.domain.model.MovieStatFacts
import org.mobyle.domain.model.PersonFact
import org.mobyle.domain.model.RatingFact
import org.mobyle.domain.model.vocabulary.Emotion
import org.mobyle.domain.model.vocabulary.Rating
import org.mobyle.domain.model.vocabulary.WatchVenue
import kotlin.test.Test
import kotlin.test.assertEquals

class StatsCalculatorTest {

    private val kurosawa = PersonFact(5026, "Akira Kurosawa", null)
    private val mifune = PersonFact(7450, "Toshiro Mifune", null)

    private val movies = mapOf(
        1L to movie(1, 1950, listOf(18 to "Drama"), setOf("JP"), "ja", listOf(kurosawa), listOf(mifune)),
        2L to movie(2, 1985, listOf(18 to "Drama", 10752 to "Guerra"), setOf("JP", "FR"), "ja", listOf(kurosawa), emptyList()),
        3L to movie(3, 2002, listOf(80 to "Crime"), setOf("BR"), "pt", emptyList(), emptyList())
    )

    private fun movie(
        id: Long, year: Int, genres: List<Pair<Int, String>>, countries: Set<String>, language: String,
        directors: List<PersonFact>, actors: List<PersonFact>
    ) = MovieStatFacts(id, id.toInt() * 100, "Movie $id", null, year, genres, countries, language, directors, actors)

    private fun log(movie: Long, day: String, minutes: Int?, completed: Boolean = true, cycle: Int = 1,
                    venue: WatchVenue? = null, emotions: List<Emotion> = emptyList()) =
        LogFact(movie, LocalDate.parse(day), minutes, completed, cycle, venue, emotions)

    @Test
    fun `movies count once per breakdown and minutes count every session`() {
        val logs = listOf(
            log(1, "2026-01-10", 40, completed = false, venue = WatchVenue.STREAMING),
            log(1, "2026-01-11", 48, venue = WatchVenue.STREAMING, emotions = listOf(Emotion.MOVED)),
            log(2, "2026-03-02", 162, venue = WatchVenue.CINEMA, emotions = listOf(Emotion.MOVED, Emotion.TENSION)),
            log(2, "2026-09-01", 162, cycle = 2),
            // Started, never finished: minutes count, the movie does not.
            log(3, "2026-03-20", 30, completed = false)
        )
        val ratings = mapOf(1L to RatingFact(Rating.LIKED), 2L to RatingFact(Rating.FAVORITE))

        val stats = StatsCalculator.year(2026, logs, movies, ratings)

        assertEquals(2, stats.movies)
        assertEquals(5, stats.sessions)
        assertEquals(442, stats.minutes)
        assertEquals(1, stats.rewatches)
        assertEquals(listOf("18", "10752"), stats.genres.map { it.key })
        assertEquals(2, stats.genres.first().movies)
        assertEquals(88 + 324, stats.genres.first().minutes)
        assertEquals(listOf("1950", "1980"), stats.decades.map { it.key }.sorted())
        assertEquals(mapOf("JP" to 2, "FR" to 1), stats.countries.associate { it.key to it.movies })
        assertEquals(listOf("ja"), stats.languages.map { it.key })
        assertEquals("5026", stats.directors.single().key)
        assertEquals("Akira Kurosawa", stats.directors.single().label)
        assertEquals(listOf("moved", "tension"), stats.emotions.map { it.key })
        assertEquals(2, stats.emotions.first().movies)
        assertEquals(setOf("streaming", "cinema"), stats.venues.map { it.key }.toSet())
        // In the order of the vocabulary, not by count.
        assertEquals(listOf("favorite", "liked"), stats.ratings.map { it.key })
        assertEquals(1, stats.months[0].movies)
        assertEquals(2, stats.months[0].sessions)
        assertEquals(1, stats.months[2].movies)
        assertEquals(192, stats.months[2].minutes)
        assertEquals(12, stats.months.size)
    }

    @Test
    fun `the calendar sums each day and people rank by movies`() {
        val logs = listOf(log(1, "2026-01-10", 40), log(2, "2026-01-10", 60), log(3, "2026-02-01", null))

        val days = StatsCalculator.calendar(logs)
        val top = StatsCalculator.topPeople(movies.values.toList(), { it.directors }, 5)

        assertEquals(listOf(2, 1), days.map { it.sessions })
        assertEquals(100, days.first().minutes)
        assertEquals("Akira Kurosawa", top.single().name)
        assertEquals(2, top.single().movies)
    }

    @Test
    fun `an empty year has zeros`() {
        val stats = StatsCalculator.year(2026, emptyList(), emptyMap(), emptyMap())

        assertEquals(0, stats.movies)
        assertEquals(0, stats.minutes)
        assertEquals(List(12) { 0 }, stats.months.map { it.movies })
    }
}
