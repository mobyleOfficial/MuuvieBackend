package org.mobyle.data.local.gamification

import io.mockk.mockk
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import org.jetbrains.exposed.sql.Database
import org.mobyle.data.local.database.DatabaseMigrations
import org.mobyle.data.local.feed.ActivityDataSourceImpl
import org.mobyle.data.local.shelves.ShelfDataSourceImpl
import org.mobyle.data.local.user.UserDatabaseDataSourceImpl
import org.mobyle.data.local.watch.WatchDataSourceImpl
import org.mobyle.data.repository.ActivityRepositoryImpl
import org.mobyle.data.repository.ShelfRepositoryImpl
import org.mobyle.data.repository.WatchRepositoryImpl
import org.mobyle.domain.model.ChallengeCriteria
import org.mobyle.domain.model.ConflictException
import org.mobyle.domain.model.DiaryMood
import org.mobyle.domain.model.Movie
import org.mobyle.domain.model.MovieFacts
import org.mobyle.domain.model.MissionType
import org.mobyle.domain.model.Page
import org.mobyle.domain.model.User
import org.mobyle.domain.model.ValidationException
import org.mobyle.domain.model.vocabulary.ActivitySubject
import org.mobyle.domain.model.vocabulary.ActivityType
import org.mobyle.domain.policy.MissionCatalog
import org.mobyle.domain.repository.MaratonaCatalog
import org.mobyle.domain.usecase.feed.ActivityPublisher
import org.mobyle.domain.usecase.gamification.ChooseStarterPet
import org.mobyle.domain.usecase.gamification.DiaryDayInput
import org.mobyle.domain.usecase.gamification.DiaryTaskInput
import org.mobyle.domain.usecase.gamification.GamificationHook
import org.mobyle.domain.usecase.gamification.GetDiaryDay
import org.mobyle.domain.usecase.gamification.GetDiaryMonth
import org.mobyle.domain.usecase.gamification.GetDiaryYear
import org.mobyle.domain.usecase.gamification.GetPetShelter
import org.mobyle.domain.usecase.gamification.PetShelterBuilder
import org.mobyle.domain.usecase.gamification.PetUnlocker
import org.mobyle.domain.usecase.gamification.PinPet
import org.mobyle.domain.usecase.gamification.SaveDiaryDay
import org.mobyle.domain.usecase.gamification.WeeklyMissions
import org.mobyle.domain.usecase.watch.LogWatchSession
import org.mobyle.domain.usecase.watch.WatchLogInput
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Bichinhos, missions and the Caderninho against Postgres (B10). */
class GamificationDataSourceImplTest {

    private lateinit var dataSource: DataSource
    private val gamification = GamificationDataSourceImpl()
    private val shelves = ShelfRepositoryImpl(ShelfDataSourceImpl())
    private val activityRepository = ActivityRepositoryImpl(ActivityDataSourceImpl())
    private val activities = ActivityPublisher(activityRepository)
    private val users = UserDatabaseDataSourceImpl()
    private val catalog = FakeCatalog()

    // A past week whose missions count movies, so sessions can be logged inside it.
    private val missionWeek: LocalDate = generateSequence(MissionCatalog.weekStart(LocalDate(2026, 10, 12))) { it.minus(DatePeriod(days = 7)) }
        .first { week -> MissionCatalog.of(week).any { it.type == MissionType.MOVIES && it.target == 2 } }
    private var today = missionWeek.plus(DatePeriod(days = 3))

    private val builder = PetShelterBuilder(gamification)
    private val unlocker = PetUnlocker(gamification, activities)
    private val shelter = GetPetShelter(builder)
    private val choose = ChooseStarterPet(gamification, unlocker, builder, activities)
    private val pin = PinPet(gamification, builder)
    private val missions = WeeklyMissions(gamification, catalog) { today }
    private val hook = GamificationHook(gamification, missions, unlocker)
    private val logSession = LogWatchSession(WatchRepositoryImpl(WatchDataSourceImpl()), shelves, activities, gamification = hook)
    private val getDay = GetDiaryDay(gamification) { today }
    private val saveDay = SaveDiaryDay(gamification, shelves) { today }
    private val month = GetDiaryMonth(gamification)
    private val year = GetDiaryYear(gamification)

    @BeforeTest
    fun setUp() {
        val name = "gamification_${counter.incrementAndGet()}"
        postgres.postgresDatabase.connection.use { it.createStatement().execute("CREATE DATABASE $name") }
        dataSource = postgres.getDatabase("postgres", name)
        DatabaseMigrations.migrate(dataSource)
        Database.connect(dataSource)

        listOf("ana", "bia").forEach {
            users.save(User(id = it, email = "$it@x.com", username = it, avatar = null, bio = null, createdAt = ""))
        }
        execute(
            """
            INSERT INTO movies (id, tmdb_id, title, "year", runtime)
            SELECT 100 + g, 1000 + g, 'Filme ' || g, 1950 + g, 100 FROM generate_series(1, 12) g;
            """
        )
    }

    @Test
    fun `the starter pack comes first and the choice is for good`() {
        val before = shelter("ana")
        assertFalse(before.starterChosen)
        assertEquals(listOf("pipoca-doce", "pipoca-salgada", "pipoca-caramelo"), before.pets.map { it.slug })

        val after = choose("ana", "pipoca-salgada")

        assertTrue(after.starterChosen)
        assertEquals("pipoca-salgada", after.pinned?.slug)
        assertEquals(1, after.pinned?.level)
        assertFalse(after.pets.any { it.slug == "pipoca-doce" })
        assertTrue(after.pets.any { it.slug == "claquete" && !it.unlocked })
        assertFailsWith<ConflictException> { choose("ana", "pipoca-doce") }
        assertFailsWith<ValidationException> { choose("bia", "claquete") }
        assertEquals(
            listOf(ActivityType.UNLOCKED_BICHINHO),
            activityRepository.findBySubject("ana", ActivitySubject.BICHINHO, after.pinned!!.let { gamification.ownedPets("ana").single().id })
                .map { it.type }
        )
    }

    @Test
    fun `sessions give XP to the pinned bichinho and unlock new ones`() {
        choose("ana", "pipoca-doce")

        (1..10).forEach { logSession("ana", 100L + it, null, null, WatchLogInput(watchedOn = "2026-01-${10 + it}")) }

        val result = shelter("ana")
        val claquete = result.pets.single { it.slug == "claquete" }
        assertTrue(claquete.unlocked)
        assertTrue(result.pinned!!.xp >= 10 * 30)
        assertTrue(result.pinned!!.level >= 3)
        assertEquals(10, result.pets.single { it.slug == "rolo-de-filme" }.progress)
        // 10 movies before 1970 too.
        assertTrue(result.pets.single { it.slug == "dinossauro" }.unlocked)

        pin("ana", "claquete")
        assertEquals("claquete", shelter("ana").pinned?.slug)
        // 1,000 minutes unlock the ingresso.
        assertEquals(listOf("claquete", "dinossauro", "ingresso", "pipoca-doce"), shelter.public("ana").pets.map { it.slug }.sorted())
    }

    @Test
    fun `imports give nothing and the bichinho needs a starter`() {
        logSession("bia", 101, null, null, WatchLogInput(watchedOn = "2026-01-10"))
        assertTrue(gamification.ownedPets("bia").isEmpty())

        execute("UPDATE watch_logs SET import_source = 'filmow'")
        choose("bia", "pipoca-doce")
        assertEquals(0, shelter("bia").pets.single { it.slug == "claquete" }.progress)
    }

    @Test
    fun `a mission gives its XP once`() {
        choose("ana", "pipoca-doce")
        val day = missionWeek.plus(DatePeriod(days = 1)).toString()

        logSession("ana", 101, null, null, WatchLogInput(watchedOn = day))
        val xpBefore = shelter("ana").pinned!!.xp
        logSession("ana", 102, null, null, WatchLogInput(watchedOn = day))

        val week = missions("ana")
        val twoMovies = week.missions.single { it.slug == "two-movies" }
        assertTrue(twoMovies.completed)
        assertEquals(2, twoMovies.progress)
        val xpAfter = shelter("ana").pinned!!.xp
        assertEquals(xpBefore + 30 + 40, xpAfter)
        missions("ana")
        assertEquals(xpAfter, shelter("ana").pinned!!.xp)
        assertEquals(missionWeek, week.weekStart)
    }

    @Test
    fun `reading a day never creates it and saving it empty deletes it`() {
        val date = LocalDate(2026, 10, 5)
        assertFalse(getDay("ana", date).saved)
        assertTrue(month("ana", 2026, 10).isEmpty())

        val saved = saveDay(
            "ana", date,
            DiaryDayInput(
                mood = "good", note = " Dia de festival ",
                tasks = listOf(
                    DiaryTaskInput("movie", "Ver Rashomon", movieId = 101),
                    DiaryTaskInput("study", "Ler sobre Kurosawa", done = true)
                )
            )
        )

        assertTrue(saved.saved)
        assertEquals(DiaryMood.GOOD, saved.mood)
        assertEquals("Dia de festival", saved.note)
        assertEquals(listOf("Ver Rashomon", "Ler sobre Kurosawa"), saved.tasks.map { it.description })
        assertEquals("Filme 1", saved.tasks.first().movie?.title)
        assertEquals(1, month("ana", 2026, 10).single().done)
        saveDay("ana", LocalDate(2026, 10, 6), DiaryDayInput(mood = "good"))
        saveDay("ana", LocalDate(2026, 9, 6), DiaryDayInput(mood = "bad"))
        val summary = year("ana", 2026)
        assertEquals(3, summary.days)
        assertEquals(DiaryMood.GOOD, summary.topMood)
        assertEquals(mapOf("good" to 2), summary.months[9].moods)

        saveDay("ana", date, DiaryDayInput())
        assertFalse(getDay("ana", date).saved)
        assertNull(gamification.diaryDay("ana", date))
    }

    @Test
    fun `a day has at most six goals and no future`() {
        val tasks = (1..7).map { DiaryTaskInput("other", "Meta $it") }

        assertFailsWith<ValidationException> { saveDay("ana", LocalDate(2026, 10, 5), DiaryDayInput(tasks = tasks)) }
        assertFailsWith<ValidationException> { saveDay("ana", LocalDate(2026, 10, 5), DiaryDayInput(mood = "sleepy")) }
        assertFailsWith<ValidationException> { getDay("ana", today.plus(DatePeriod(days = 5))) }
    }

    private class FakeCatalog : MaratonaCatalog {
        override fun facts(movieId: Long): MovieFacts? = null

        override fun suggestions(criteria: ChallengeCriteria, page: Int): Page<Movie> = Page(emptyList(), page, 0, 0, false)
    }

    private fun execute(sql: String) = dataSource.connection.use { it.createStatement().execute(sql) }

    companion object {
        private val postgres by lazy { EmbeddedPostgres.start() }
        private val counter = AtomicInteger()
    }
}
