package org.mobyle.domain.usecase.movies

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import org.mobyle.domain.model.MonthActivity
import org.mobyle.domain.model.MovieTimeline
import org.mobyle.domain.model.ValidationException
import org.mobyle.domain.repository.WatchRepository
import org.mobyle.domain.usecase.watch.today

/**
 * The timeline chart of the movie detail's Comunidade (B13.5): public sessions and public
 * ratings per calendar month, for the last [months] months including the current one.
 */
class GetMovieTimeline(
    private val repository: WatchRepository,
    private val today: () -> LocalDate = ::today
) {
    operator fun invoke(movieId: Long, months: Int = DEFAULT_MONTHS): MovieTimeline {
        if (months !in 1..MAX_MONTHS) throw ValidationException("months", "months must be between 1 and $MAX_MONTHS")
        val currentMonth = today().let { LocalDate(it.year, it.monthNumber, 1) }
        val firstMonth = currentMonth.minus(DatePeriod(months = months - 1))
        val activity = repository.activity(movieId, firstMonth, currentMonth.plus(DatePeriod(months = 1)))
        val sessions = activity.sessionsByDay.byMonth()
        val ratings = activity.ratingsByDay.byMonth()
        val timeline = (0 until months).map { offset ->
            val month = monthKey(firstMonth.plus(DatePeriod(months = offset)))
            MonthActivity(month = month, sessions = sessions[month] ?: 0, ratings = ratings[month] ?: 0)
        }
        return MovieTimeline(timeline)
    }

    private fun Map<LocalDate, Int>.byMonth(): Map<String, Int> =
        entries.groupBy({ monthKey(it.key) }, { it.value }).mapValues { it.value.sum() }

    private fun monthKey(date: LocalDate): String = "${date.year}-${date.monthNumber.toString().padStart(2, '0')}"

    companion object {
        const val DEFAULT_MONTHS = 12
        const val MAX_MONTHS = 24
    }
}
