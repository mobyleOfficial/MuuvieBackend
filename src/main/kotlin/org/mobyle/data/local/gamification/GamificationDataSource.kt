package org.mobyle.data.local.gamification

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.DatePeriod
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.mobyle.data.local.database.ActivitiesTable
import org.mobyle.data.local.database.CineClubeParticipationsTable
import org.mobyle.data.local.database.DiaryDaysTable
import org.mobyle.data.local.database.DiaryTasksTable
import org.mobyle.data.local.database.GenresTable
import org.mobyle.data.local.database.MovieGenresTable
import org.mobyle.data.local.database.MoviesTable
import org.mobyle.data.local.database.UserMissionsTable
import org.mobyle.data.local.database.UserPetsTable
import org.mobyle.data.local.database.WatchLogsTable
import org.mobyle.data.local.database.userDbIdOf
import org.mobyle.domain.model.DiaryCategory
import org.mobyle.domain.model.DiaryDay
import org.mobyle.domain.model.DiaryDaySummary
import org.mobyle.domain.model.DiaryDraft
import org.mobyle.domain.model.DiaryMood
import org.mobyle.domain.model.DiaryTask
import org.mobyle.domain.model.MovieCard
import org.mobyle.domain.model.OwnedPet
import org.mobyle.domain.model.PetProgress
import org.mobyle.domain.model.WeekActivity
import org.mobyle.domain.model.vocabulary.ActivityType
import org.mobyle.domain.repository.GamificationRepository

/** Exposed implementation of the gamification; same contract as [GamificationRepository]. */
interface GamificationDataSource : GamificationRepository

class GamificationDataSourceImpl : GamificationDataSource {

    override fun ownedPets(userId: String): List<OwnedPet> = transaction {
        val user = userDbIdOf(userId) ?: return@transaction emptyList()
        UserPetsTable.selectAll()
            .where { UserPetsTable.userId eq user }
            .orderBy(UserPetsTable.unlockedAt to SortOrder.ASC, UserPetsTable.id to SortOrder.ASC)
            .map { it.toOwnedPet() }
    }

    override fun addPet(userId: String, slug: String, starter: Boolean, pinned: Boolean): OwnedPet? = transaction {
        val user = userDbIdOf(userId) ?: throw IllegalArgumentException("Unknown user $userId")
        val inserted = UserPetsTable.insertIgnore {
            it[UserPetsTable.userId] = user
            it[petSlug] = slug
            it[UserPetsTable.starter] = starter
            it[UserPetsTable.pinned] = pinned
        }.insertedCount > 0
        if (!inserted) return@transaction null
        UserPetsTable.selectAll()
            .where { (UserPetsTable.userId eq user) and (UserPetsTable.petSlug eq slug) }
            .single()
            .toOwnedPet()
    }

    override fun pin(userId: String, slug: String): Boolean = transaction {
        val user = userDbIdOf(userId) ?: return@transaction false
        val owned = !UserPetsTable.selectAll()
            .where { (UserPetsTable.userId eq user) and (UserPetsTable.petSlug eq slug) }
            .empty()
        if (!owned) return@transaction false
        UserPetsTable.update({ (UserPetsTable.userId eq user) and (UserPetsTable.pinned eq true) }) { it[pinned] = false }
        UserPetsTable.update({ (UserPetsTable.userId eq user) and (UserPetsTable.petSlug eq slug) }) { it[pinned] = true }
        true
    }

    override fun addXp(userId: String, xp: Int) {
        if (xp <= 0) return
        transaction {
            val user = userDbIdOf(userId) ?: return@transaction
            UserPetsTable.update({ (UserPetsTable.userId eq user) and (UserPetsTable.pinned eq true) }) {
                it[UserPetsTable.xp] = UserPetsTable.xp + xp
            }
        }
    }

    override fun petProgress(userId: String, genreIds: Set<Int>, beforeYears: Set<Int>): PetProgress = transaction {
        val user = userDbIdOf(userId) ?: return@transaction PetProgress(0, 0, emptyMap(), emptyMap(), 0, 0, 0)
        val own = (WatchLogsTable.userId eq user) and WatchLogsTable.importSource.isNull()
        val finished = WatchLogsTable.select(WatchLogsTable.movieId)
            .where { own and (WatchLogsTable.completed eq true) }
            .withDistinct()
            .map { it[WatchLogsTable.movieId].value }
        val minutes = WatchLogsTable.select(WatchLogsTable.minutesWatched)
            .where { own }
            .sumOf { it[WatchLogsTable.minutesWatched] ?: 0 }
        val genres = if (finished.isEmpty() || genreIds.isEmpty()) emptyMap() else
            (MovieGenresTable innerJoin GenresTable).select(MovieGenresTable.movieId, GenresTable.tmdbId)
                .where { (MovieGenresTable.movieId inList finished) and (GenresTable.tmdbId inList genreIds) }
                .map { it[GenresTable.tmdbId] to it[MovieGenresTable.movieId].value }
                .distinct()
                .groupingBy { it.first }
                .eachCount()
        val years = if (finished.isEmpty()) emptyList() else MoviesTable.select(MoviesTable.year, MoviesTable.releaseDate)
            .where { MoviesTable.id inList finished }
            .mapNotNull { it[MoviesTable.year] ?: it[MoviesTable.releaseDate]?.take(4)?.toIntOrNull() }
        PetProgress(
            movies = finished.size,
            minutes = minutes,
            genreMovies = genres,
            oldMovies = beforeYears.associateWith { limit -> years.count { it < limit } },
            maratonasFinished = ActivitiesTable.selectAll()
                .where { (ActivitiesTable.actorId eq user) and (ActivitiesTable.type eq ActivityType.FINISHED_MARATONA.wireValue) }
                .count().toInt(),
            cineclubesJoined = CineClubeParticipationsTable.selectAll()
                .where { CineClubeParticipationsTable.userId eq user }
                .count().toInt(),
            missions = UserMissionsTable.selectAll().where { UserMissionsTable.userId eq user }.count().toInt()
        )
    }

    override fun weekActivity(userId: String, from: LocalDate, to: LocalDate): WeekActivity = transaction {
        val user = userDbIdOf(userId) ?: return@transaction WeekActivity(0, 0, emptyList(), 0, 0)
        val logs = WatchLogsTable.select(WatchLogsTable.movieId, WatchLogsTable.completed, WatchLogsTable.emotionIds)
            .where {
                (WatchLogsTable.userId eq user) and WatchLogsTable.importSource.isNull() and
                    (WatchLogsTable.watchedOn greaterEq from) and (WatchLogsTable.watchedOn lessEq to)
            }
            .toList()
        val start = from.atStartOfDayIn(TimeZone.UTC)
        val end = to.plus(DatePeriod(days = 1)).atStartOfDayIn(TimeZone.UTC)
        WeekActivity(
            sessions = logs.size,
            sessionsWithEmotions = logs.count { it[WatchLogsTable.emotionIds].isNotEmpty() },
            finishedMovieIds = logs.filter { it[WatchLogsTable.completed] }.map { it[WatchLogsTable.movieId].value }.distinct(),
            cineclubesJoined = CineClubeParticipationsTable.selectAll()
                .where {
                    (CineClubeParticipationsTable.userId eq user) and (CineClubeParticipationsTable.joinedAt greaterEq start) and
                        (CineClubeParticipationsTable.joinedAt less end)
                }
                .count().toInt(),
            challengesCompleted = ActivitiesTable.selectAll()
                .where {
                    (ActivitiesTable.actorId eq user) and (ActivitiesTable.type eq ActivityType.FINISHED_CHALLENGE.wireValue) and
                        (ActivitiesTable.createdAt greaterEq start) and (ActivitiesTable.createdAt less end)
                }
                .count().toInt()
        )
    }

    override fun completedMissions(userId: String, weekStart: LocalDate): Map<String, Instant> = transaction {
        val user = userDbIdOf(userId) ?: return@transaction emptyMap()
        UserMissionsTable.selectAll()
            .where { (UserMissionsTable.userId eq user) and (UserMissionsTable.weekStart eq weekStart) }
            .associate { it[UserMissionsTable.missionSlug] to it[UserMissionsTable.completedAt] }
    }

    override fun completeMission(userId: String, weekStart: LocalDate, slug: String, xp: Int): Boolean = transaction {
        val user = userDbIdOf(userId) ?: return@transaction false
        UserMissionsTable.insertIgnore {
            it[UserMissionsTable.userId] = user
            it[UserMissionsTable.weekStart] = weekStart
            it[missionSlug] = slug
            it[UserMissionsTable.xp] = xp
        }.insertedCount > 0
    }

    // ── Caderninho ──────────────────────────────────────────────────────────

    override fun diaryDay(userId: String, date: LocalDate): DiaryDay? = transaction {
        val user = userDbIdOf(userId) ?: return@transaction null
        val row = DiaryDaysTable.selectAll()
            .where { (DiaryDaysTable.userId eq user) and (DiaryDaysTable.day eq date) }
            .firstOrNull() ?: return@transaction null
        val tasks = DiaryTasksTable.leftJoinMovies().selectAll()
            .where { DiaryTasksTable.dayId eq row[DiaryDaysTable.id] }
            .orderBy(DiaryTasksTable.position to SortOrder.ASC, DiaryTasksTable.id to SortOrder.ASC)
            .map { it.toTask() }
        DiaryDay(
            date = date,
            mood = row[DiaryDaysTable.mood]?.let(DiaryMood::fromWireValue),
            note = row[DiaryDaysTable.note],
            tasks = tasks,
            saved = true
        )
    }

    override fun saveDiaryDay(userId: String, date: LocalDate, draft: DiaryDraft) {
        transaction {
            val user = userDbIdOf(userId) ?: throw IllegalArgumentException("Unknown user $userId")
            val existing = DiaryDaysTable.select(DiaryDaysTable.id)
                .where { (DiaryDaysTable.userId eq user) and (DiaryDaysTable.day eq date) }
                .firstOrNull()
                ?.get(DiaryDaysTable.id)?.value
            val dayId = if (existing != null) {
                DiaryDaysTable.update({ DiaryDaysTable.id eq existing }) {
                    it[mood] = draft.mood?.wireValue
                    it[note] = draft.note
                    it[updatedAt] = Clock.System.now()
                }
                DiaryTasksTable.deleteWhere { dayId eq existing }
                existing
            } else {
                DiaryDaysTable.insertAndGetId {
                    it[DiaryDaysTable.userId] = user
                    it[day] = date
                    it[mood] = draft.mood?.wireValue
                    it[note] = draft.note
                }.value
            }
            draft.tasks.forEachIndexed { index, task ->
                DiaryTasksTable.insert {
                    it[DiaryTasksTable.dayId] = dayId
                    it[category] = task.category.wireValue
                    it[description] = task.description
                    it[done] = task.done
                    it[position] = index
                    it[movieId] = task.movieId
                }
            }
        }
    }

    override fun deleteDiaryDay(userId: String, date: LocalDate) {
        transaction {
            val user = userDbIdOf(userId) ?: return@transaction
            DiaryDaysTable.deleteWhere { (DiaryDaysTable.userId eq user) and (day eq date) }
        }
    }

    override fun diaryDays(userId: String, from: LocalDate, to: LocalDate): List<DiaryDaySummary> = transaction {
        val user = userDbIdOf(userId) ?: return@transaction emptyList()
        val days = DiaryDaysTable.selectAll()
            .where { (DiaryDaysTable.userId eq user) and (DiaryDaysTable.day greaterEq from) and (DiaryDaysTable.day lessEq to) }
            .orderBy(DiaryDaysTable.day to SortOrder.ASC)
            .toList()
        val tasks = if (days.isEmpty()) emptyMap() else DiaryTasksTable.select(DiaryTasksTable.dayId, DiaryTasksTable.done)
            .where { DiaryTasksTable.dayId inList days.map { it[DiaryDaysTable.id] } }
            .groupBy({ it[DiaryTasksTable.dayId].value }, { it[DiaryTasksTable.done] })
        days.map { row ->
            val own = tasks[row[DiaryDaysTable.id].value].orEmpty()
            DiaryDaySummary(
                date = row[DiaryDaysTable.day],
                mood = row[DiaryDaysTable.mood]?.let(DiaryMood::fromWireValue),
                tasks = own.size,
                done = own.count { it },
                hasNote = row[DiaryDaysTable.note] != null
            )
        }
    }

    override fun diaryMoods(userId: String, from: LocalDate, to: LocalDate): List<Pair<LocalDate, DiaryMood?>> = transaction {
        val user = userDbIdOf(userId) ?: return@transaction emptyList()
        DiaryDaysTable.select(DiaryDaysTable.day, DiaryDaysTable.mood)
            .where { (DiaryDaysTable.userId eq user) and (DiaryDaysTable.day greaterEq from) and (DiaryDaysTable.day lessEq to) }
            .map { it[DiaryDaysTable.day] to it[DiaryDaysTable.mood]?.let(DiaryMood::fromWireValue) }
    }

    // ── Helpers (inside a transaction) ──────────────────────────────────────

    private fun DiaryTasksTable.leftJoinMovies() =
        join(MoviesTable, JoinType.LEFT, DiaryTasksTable.movieId, MoviesTable.id)

    private fun ResultRow.toTask() = DiaryTask(
        id = this[DiaryTasksTable.id].value,
        category = DiaryCategory.fromWireValue(this[DiaryTasksTable.category]) ?: DiaryCategory.OTHER,
        description = this[DiaryTasksTable.description],
        done = this[DiaryTasksTable.done],
        position = this[DiaryTasksTable.position],
        movie = this[DiaryTasksTable.movieId]?.let {
            MovieCard(
                movieId = it.value,
                tmdbId = this[MoviesTable.tmdbId],
                title = this[MoviesTable.localTitle] ?: this[MoviesTable.title],
                posterPath = this[MoviesTable.posterPath],
                year = this[MoviesTable.year]
            )
        }
    )

    private fun ResultRow.toOwnedPet() = OwnedPet(
        id = this[UserPetsTable.id].value,
        slug = this[UserPetsTable.petSlug],
        xp = this[UserPetsTable.xp],
        starter = this[UserPetsTable.starter],
        pinned = this[UserPetsTable.pinned],
        unlockedAt = this[UserPetsTable.unlockedAt]
    )
}
