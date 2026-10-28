package org.mobyle.domain.usecase.stats

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import org.mobyle.domain.model.CalendarDay
import org.mobyle.domain.model.MovieCard
import org.mobyle.domain.model.NotFoundException
import org.mobyle.domain.model.Retrospective
import org.mobyle.domain.model.StatsYears
import org.mobyle.domain.model.ValidationException
import org.mobyle.domain.model.YearStats
import org.mobyle.domain.model.YearlyGoal
import org.mobyle.domain.model.vocabulary.Emotion
import org.mobyle.domain.model.vocabulary.Visibility
import org.mobyle.domain.policy.StatsCalculator
import org.mobyle.domain.policy.VisibilityPolicy
import org.mobyle.domain.repository.StatsRepository
import org.mobyle.domain.usecase.shelves.ShelfAccess
import org.mobyle.domain.usecase.users.ProfileAccess
import org.mobyle.domain.usecase.watch.today

private const val MIN_YEAR = 1900
private const val MAX_TARGET = 5000
private const val TOP = 5

/** Resolves the owner (id or @handle) and what of their sessions the viewer may count. */
class StatsAccess(private val profiles: ProfileAccess, private val shelves: ShelfAccess) {
    fun owner(idOrHandle: String): String = profiles.resolveUserId(idOrHandle)

    fun visibilities(viewerId: String?, ownerId: String): Set<Visibility> =
        VisibilityPolicy.visibleTo(shelves.relation(viewerId, ownerId))
}

internal fun checkYear(year: Int, today: LocalDate) {
    if (year !in MIN_YEAR..today.year + 1) throw ValidationException("year", "Invalid year")
}

/** The years with sessions the viewer can see, newest first (the year picker, F9.2). */
class GetStatsYears(private val repository: StatsRepository, private val access: StatsAccess) {
    operator fun invoke(viewerId: String?, idOrHandle: String): StatsYears {
        val owner = access.owner(idOrHandle)
        return StatsYears(repository.years(owner, access.visibilities(viewerId, owner)))
    }
}

/** The charts of a year (F9.1, F9.2): totals, months and every breakdown. */
class GetYearStats(
    private val repository: StatsRepository,
    private val access: StatsAccess,
    private val today: () -> LocalDate = ::today
) {
    operator fun invoke(viewerId: String?, idOrHandle: String, year: Int): YearStats {
        checkYear(year, today())
        val owner = access.owner(idOrHandle)
        val visibilities = access.visibilities(viewerId, owner)
        val logs = repository.logs(owner, year, visibilities)
        val movieIds = logs.map { it.movieId }.distinct()
        return StatsCalculator.year(year, logs, repository.movies(movieIds), repository.ratings(owner, movieIds, visibilities))
    }
}

/** Sessions per day of a year, for the heatmap. */
class GetStatsCalendar(
    private val repository: StatsRepository,
    private val access: StatsAccess,
    private val today: () -> LocalDate = ::today
) {
    operator fun invoke(viewerId: String?, idOrHandle: String, year: Int): List<CalendarDay> {
        checkYear(year, today())
        val owner = access.owner(idOrHandle)
        return StatsCalculator.calendar(repository.logs(owner, year, access.visibilities(viewerId, owner)))
    }
}

@Serializable
data class RetrospectiveYear(val year: Int, val movies: Int)

/**
 * The retrospective (F9.3), only for the owner: the years with finished movies and the
 * stories of one of them.
 */
class GetRetrospective(
    private val repository: StatsRepository,
    private val today: () -> LocalDate = ::today
) {
    private val all = Visibility.entries.toSet()

    fun years(userId: String): List<RetrospectiveYear> =
        repository.years(userId, all)
            .map { RetrospectiveYear(it, repository.finishedMovies(userId, it, all)) }
            .filter { it.movies > 0 }

    operator fun invoke(userId: String, year: Int): Retrospective {
        checkYear(year, today())
        val logs = repository.logs(userId, year, all)
        if (logs.none { it.completed }) throw NotFoundException("No movies watched in $year")
        val movieIds = logs.map { it.movieId }.distinct()
        val movies = repository.movies(movieIds)
        val ratings = repository.ratings(userId, movieIds, all)
        val stats = StatsCalculator.year(year, logs, movies, ratings)
        val finished = logs.filter { it.completed }.map { it.movieId }.distinct().mapNotNull { movies[it] }
        // The best verdict of the year: Favoritado first, in the order of the vocabulary.
        val favorite = finished
            .filter { ratings[it.movieId]?.rating?.isVerdict == true }
            .minByOrNull { ratings[it.movieId]!!.rating.ordinal }
        return Retrospective(
            year = year,
            movies = stats.movies,
            sessions = stats.sessions,
            minutes = stats.minutes,
            rewatches = stats.rewatches,
            topGenres = stats.genres.take(TOP),
            topDirectors = StatsCalculator.topPeople(finished, { it.directors }, TOP),
            topActors = StatsCalculator.topPeople(finished, { it.actors }, TOP),
            emotion = stats.emotions.firstOrNull()?.let { Emotion.fromWireValue(it.key) },
            favorite = favorite?.let { MovieCard(it.movieId, it.tmdbId, it.title, it.posterPath, it.year) },
            favoriteRating = favorite?.let { ratings[it.movieId]?.rating },
            maratonasFinished = repository.maratonasFinished(userId, year),
            cineclubes = repository.cineclubesJoined(userId, year),
            bestMonth = stats.months.filter { it.movies > 0 }.maxWithOrNull(compareBy({ it.movies }, { it.minutes })),
            goal = repository.goal(userId, year)?.let { YearlyGoal(year, it, stats.movies) }
        )
    }
}

/** A user's goal for a year (F9.4), with the movies they finished as the viewer can count them. */
class GetYearlyGoal(
    private val repository: StatsRepository,
    private val access: StatsAccess,
    private val today: () -> LocalDate = ::today
) {
    operator fun invoke(viewerId: String?, idOrHandle: String, year: Int): YearlyGoal {
        checkYear(year, today())
        val owner = access.owner(idOrHandle)
        val target = repository.goal(owner, year) ?: throw NotFoundException("No goal for $year")
        return YearlyGoal(year, target, repository.finishedMovies(owner, year, access.visibilities(viewerId, owner)))
    }
}

class SetYearlyGoal(private val repository: StatsRepository, private val today: () -> LocalDate = ::today) {
    operator fun invoke(userId: String, year: Int, targetMovies: Int): YearlyGoal {
        val now = today()
        // Goals are for this year or the next one.
        if (year !in now.year..now.year + 1) throw ValidationException("year", "A goal is for this year or the next")
        if (targetMovies !in 1..MAX_TARGET) throw ValidationException("targetMovies", "The goal is between 1 and $MAX_TARGET movies")
        repository.setGoal(userId, year, targetMovies)
        return YearlyGoal(year, targetMovies, repository.finishedMovies(userId, year, Visibility.entries.toSet()))
    }

    fun remove(userId: String, year: Int) {
        if (!repository.removeGoal(userId, year)) throw NotFoundException("No goal for $year")
    }
}
