package org.mobyle.domain.usecase.discover

import kotlinx.datetime.Clock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import org.mobyle.domain.model.Locadora
import org.mobyle.domain.model.Movie
import org.mobyle.domain.model.Page
import org.mobyle.domain.model.PageRequest
import org.mobyle.domain.model.ProfileCard
import org.mobyle.domain.model.Shelf
import org.mobyle.domain.model.ValidationException
import org.mobyle.domain.repository.DiscoverRepository
import org.mobyle.domain.usecase.watch.today
import kotlin.time.Duration.Companion.days

/** Activity counts for the ranking over the last 30 days (B5). */
private val ACTIVITY_WINDOW = 30.days

/** Public shelves of other users for the Catálogo (F5.6), optionally filtered by name. */
class DiscoverShelves(private val repository: DiscoverRepository) {
    operator fun invoke(viewerId: String?, query: String?, page: PageRequest): Page<Shelf> =
        repository.shelves(viewerId, query, Clock.System.now() - ACTIVITY_WINDOW, page)
}

/** Public locadoras of other users for the Catálogo (F5.6), optionally filtered by name. */
class DiscoverLocadoras(private val repository: DiscoverRepository) {
    operator fun invoke(viewerId: String?, query: String?, page: PageRequest): Page<Locadora> =
        repository.locadoras(viewerId, query, Clock.System.now() - ACTIVITY_WINDOW, page)
}

/**
 * Profiles to follow in the Catálogo (F5.6): curated first, then the most active; the Pessoas
 * tab of the search filters them by name, @ or username (F14.2).
 */
class DiscoverProfiles(private val repository: DiscoverRepository) {
    operator fun invoke(viewerId: String?, query: String?, page: PageRequest): Page<ProfileCard> =
        repository.profiles(viewerId, query?.removePrefix("@"), Clock.System.now() - ACTIVITY_WINDOW, page)
}

/** "Em alta no Muuvie" (B14.1): the most logged movies of the last [days] days, today included. */
class DiscoverTrendingMovies(
    private val repository: DiscoverRepository,
    private val today: () -> LocalDate = ::today
) {
    operator fun invoke(days: Int, page: PageRequest): Page<Movie> {
        if (days !in 1..MAX_DAYS) throw ValidationException("days", "days must be between 1 and $MAX_DAYS")
        return repository.trendingMovies(today().minus(DatePeriod(days = days - 1)), page)
    }

    companion object {
        const val DEFAULT_DAYS = 7
        const val MAX_DAYS = 30
    }
}

/** "Mais amados no Muuvie" (B14.1): one label is not a ranking, so a movie needs [MIN_RATINGS]. */
class DiscoverLovedMovies(private val repository: DiscoverRepository) {
    operator fun invoke(page: PageRequest): Page<Movie> = repository.lovedMovies(MIN_RATINGS, page)

    companion object {
        const val MIN_RATINGS = 2
    }
}
