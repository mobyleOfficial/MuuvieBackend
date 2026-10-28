package org.mobyle.data.local.stats

import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.mobyle.data.local.database.ActivitiesTable
import org.mobyle.data.local.database.CineClubeParticipationsTable
import org.mobyle.data.local.database.GenresTable
import org.mobyle.data.local.database.MovieCastTable
import org.mobyle.data.local.database.MovieCountriesTable
import org.mobyle.data.local.database.MovieGenresTable
import org.mobyle.data.local.database.MoviesTable
import org.mobyle.data.local.database.PeopleTable
import org.mobyle.data.local.database.ShelvesTable
import org.mobyle.data.local.database.UserMoviesTable
import org.mobyle.data.local.database.WatchLogsTable
import org.mobyle.data.local.database.YearlyGoalsTable
import org.mobyle.data.local.database.userDbIdOf
import org.mobyle.domain.model.LogFact
import org.mobyle.domain.model.MovieStatFacts
import org.mobyle.domain.model.PersonFact
import org.mobyle.domain.model.RatingFact
import org.mobyle.domain.model.vocabulary.ActivityType
import org.mobyle.domain.model.vocabulary.Emotion
import org.mobyle.domain.model.vocabulary.Rating
import org.mobyle.domain.model.vocabulary.SystemShelfType
import org.mobyle.domain.model.vocabulary.Visibility
import org.mobyle.domain.model.vocabulary.WatchVenue
import org.mobyle.domain.repository.StatsRepository

/** Exposed implementation of the statistics; same contract as [StatsRepository]. */
interface StatsDataSource : StatsRepository

class StatsDataSourceImpl : StatsDataSource {

    override fun years(ownerId: String, visibilities: Set<Visibility>): List<Int> = transaction {
        val user = userDbIdOf(ownerId) ?: return@transaction emptyList()
        WatchLogsTable.select(WatchLogsTable.watchedOn)
            .where { (WatchLogsTable.userId eq user) and (WatchLogsTable.visibility inList visibilities.map { it.wireValue }) }
            .withDistinct()
            .map { it[WatchLogsTable.watchedOn].year }
            .distinct()
            .sortedDescending()
    }

    override fun logs(ownerId: String, year: Int, visibilities: Set<Visibility>): List<LogFact> = transaction {
        val user = userDbIdOf(ownerId) ?: return@transaction emptyList()
        WatchLogsTable.select(
            WatchLogsTable.movieId, WatchLogsTable.watchedOn, WatchLogsTable.minutesWatched, WatchLogsTable.completed,
            WatchLogsTable.cycle, WatchLogsTable.venue, WatchLogsTable.emotionIds
        )
            .where {
                (WatchLogsTable.userId eq user) and (WatchLogsTable.visibility inList visibilities.map { it.wireValue }) and
                    (WatchLogsTable.watchedOn greaterEq LocalDate(year, 1, 1)) and (WatchLogsTable.watchedOn lessEq LocalDate(year, 12, 31))
            }
            .orderBy(WatchLogsTable.watchedOn to SortOrder.ASC)
            .map {
                LogFact(
                    movieId = it[WatchLogsTable.movieId].value,
                    watchedOn = it[WatchLogsTable.watchedOn],
                    minutes = it[WatchLogsTable.minutesWatched],
                    completed = it[WatchLogsTable.completed],
                    cycle = it[WatchLogsTable.cycle],
                    venue = it[WatchLogsTable.venue]?.let(WatchVenue::fromWireValue),
                    emotions = it[WatchLogsTable.emotionIds].mapNotNull { id -> Emotion.fromId(id.toInt()) }
                )
            }
    }

    override fun movies(movieIds: Collection<Long>): Map<Long, MovieStatFacts> = transaction {
        if (movieIds.isEmpty()) return@transaction emptyMap()
        val ids = movieIds.distinct()
        val genres = (MovieGenresTable innerJoin GenresTable).select(MovieGenresTable.movieId, GenresTable.tmdbId, GenresTable.name)
            .where { MovieGenresTable.movieId inList ids }
            .groupBy({ it[MovieGenresTable.movieId].value }, { it[GenresTable.tmdbId] to it[GenresTable.name] })
        val countries = MovieCountriesTable.select(MovieCountriesTable.movieId, MovieCountriesTable.country)
            .where { MovieCountriesTable.movieId inList ids }
            .groupBy({ it[MovieCountriesTable.movieId].value }, { it[MovieCountriesTable.country] })
        val people = (MovieCastTable innerJoin PeopleTable)
            .select(MovieCastTable.movieId, MovieCastTable.role, MovieCastTable.position, PeopleTable.tmdbId, PeopleTable.name, PeopleTable.profilePath)
            .where { MovieCastTable.movieId inList ids }
            .orderBy(MovieCastTable.position to SortOrder.ASC)
            .groupBy { it[MovieCastTable.movieId].value }
        MoviesTable.selectAll().where { MoviesTable.id inList ids }.associate { row ->
            val id = row[MoviesTable.id].value
            val cast = people[id].orEmpty()
            fun personOf(it: org.jetbrains.exposed.sql.ResultRow) =
                PersonFact(it[PeopleTable.tmdbId], it[PeopleTable.name], it[PeopleTable.profilePath])
            id to MovieStatFacts(
                movieId = id,
                tmdbId = row[MoviesTable.tmdbId],
                title = row[MoviesTable.localTitle] ?: row[MoviesTable.title],
                posterPath = row[MoviesTable.posterPath],
                year = row[MoviesTable.year] ?: row[MoviesTable.releaseDate]?.take(4)?.toIntOrNull(),
                genres = genres[id].orEmpty(),
                countries = countries[id].orEmpty().toSet(),
                language = row[MoviesTable.originalLanguage],
                directors = cast.filter { it[MovieCastTable.role] == "director" }.map(::personOf),
                // Leads only: the first billed names.
                actors = cast.filter { it[MovieCastTable.role] == "actor" }.take(LEAD_ACTORS).map(::personOf)
            )
        }
    }

    override fun ratings(ownerId: String, movieIds: Collection<Long>, visibilities: Set<Visibility>): Map<Long, RatingFact> = transaction {
        val user = userDbIdOf(ownerId) ?: return@transaction emptyMap()
        if (movieIds.isEmpty()) return@transaction emptyMap()
        val watchedVisibility = ShelvesTable.select(ShelvesTable.visibility)
            .where { (ShelvesTable.userId eq user) and (ShelvesTable.systemType eq SystemShelfType.WATCHED.wireValue) }
            .firstOrNull()
            ?.let { Visibility.fromWireValue(it[ShelvesTable.visibility]) }
            ?: Visibility.DEFAULT
        if (watchedVisibility !in visibilities) return@transaction emptyMap()
        UserMoviesTable.select(UserMoviesTable.movieId, UserMoviesTable.rating)
            .where { (UserMoviesTable.userId eq user) and (UserMoviesTable.movieId inList movieIds.distinct()) }
            .mapNotNull { row ->
                val rating = row[UserMoviesTable.rating]?.let { Rating.fromWireValue(it) } ?: return@mapNotNull null
                row[UserMoviesTable.movieId].value to RatingFact(rating)
            }
            .toMap()
    }

    override fun finishedMovies(ownerId: String, year: Int, visibilities: Set<Visibility>): Int = transaction {
        val user = userDbIdOf(ownerId) ?: return@transaction 0
        WatchLogsTable.select(WatchLogsTable.movieId)
            .where {
                (WatchLogsTable.userId eq user) and (WatchLogsTable.completed eq true) and
                    (WatchLogsTable.visibility inList visibilities.map { it.wireValue }) and
                    (WatchLogsTable.watchedOn greaterEq LocalDate(year, 1, 1)) and (WatchLogsTable.watchedOn lessEq LocalDate(year, 12, 31))
            }
            .withDistinct()
            .count()
            .toInt()
    }

    override fun maratonasFinished(ownerId: String, year: Int): Int = transaction {
        val user = userDbIdOf(ownerId) ?: return@transaction 0
        ActivitiesTable.selectAll()
            .where {
                (ActivitiesTable.actorId eq user) and (ActivitiesTable.type eq ActivityType.FINISHED_MARATONA.wireValue) and
                    (ActivitiesTable.createdAt greaterEq startOf(year)) and (ActivitiesTable.createdAt less startOf(year + 1))
            }
            .count()
            .toInt()
    }

    override fun cineclubesJoined(ownerId: String, year: Int): Int = transaction {
        val user = userDbIdOf(ownerId) ?: return@transaction 0
        CineClubeParticipationsTable.selectAll()
            .where {
                (CineClubeParticipationsTable.userId eq user) and
                    (CineClubeParticipationsTable.joinedAt greaterEq startOf(year)) and
                    (CineClubeParticipationsTable.joinedAt less startOf(year + 1))
            }
            .count()
            .toInt()
    }

    override fun goal(ownerId: String, year: Int): Int? = transaction {
        val user = userDbIdOf(ownerId) ?: return@transaction null
        YearlyGoalsTable.select(YearlyGoalsTable.targetMovies)
            .where { (YearlyGoalsTable.userId eq user) and (YearlyGoalsTable.year eq year) }
            .firstOrNull()
            ?.get(YearlyGoalsTable.targetMovies)
    }

    override fun setGoal(ownerId: String, year: Int, targetMovies: Int) {
        transaction {
            val user = userDbIdOf(ownerId) ?: throw IllegalArgumentException("Unknown user $ownerId")
            val updated = YearlyGoalsTable.update({ (YearlyGoalsTable.userId eq user) and (YearlyGoalsTable.year eq year) }) {
                it[YearlyGoalsTable.targetMovies] = targetMovies
                it[updatedAt] = Clock.System.now()
            }
            if (updated == 0) {
                YearlyGoalsTable.insert {
                    it[userId] = user
                    it[YearlyGoalsTable.year] = year
                    it[YearlyGoalsTable.targetMovies] = targetMovies
                }
            }
        }
    }

    override fun removeGoal(ownerId: String, year: Int): Boolean = transaction {
        val user = userDbIdOf(ownerId) ?: return@transaction false
        YearlyGoalsTable.deleteWhere { (YearlyGoalsTable.userId eq user) and (YearlyGoalsTable.year eq year) } > 0
    }

    private fun startOf(year: Int) = LocalDate(year, 1, 1).atStartOfDayIn(TimeZone.UTC)

    private companion object {
        const val LEAD_ACTORS = 5
    }
}
