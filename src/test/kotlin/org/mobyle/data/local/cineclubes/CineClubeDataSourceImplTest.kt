package org.mobyle.data.local.cineclubes

import io.mockk.mockk
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import org.jetbrains.exposed.sql.Database
import org.mobyle.data.local.database.DatabaseMigrations
import org.mobyle.data.local.feed.ActivityDataSourceImpl
import org.mobyle.data.local.shelves.ShelfDataSourceImpl
import org.mobyle.data.local.user.UserDatabaseDataSourceImpl
import org.mobyle.data.local.watch.WatchDataSourceImpl
import org.mobyle.data.repository.ActivityRepositoryImpl
import org.mobyle.data.repository.ShelfRepositoryImpl
import org.mobyle.data.repository.WatchRepositoryImpl
import org.mobyle.data.service.BcryptPasswordHasher
import org.mobyle.domain.model.CineClubeFilter
import org.mobyle.domain.model.ConflictException
import org.mobyle.domain.model.ForbiddenException
import org.mobyle.domain.model.PageRequest
import org.mobyle.domain.model.SocialEvent
import org.mobyle.domain.model.User
import org.mobyle.domain.model.ValidationException
import org.mobyle.domain.model.WatchProvider
import org.mobyle.domain.model.vocabulary.ActivitySubject
import org.mobyle.domain.model.vocabulary.ActivityType
import org.mobyle.domain.model.vocabulary.CineClubeStatus
import org.mobyle.domain.model.vocabulary.SessionProgress
import org.mobyle.domain.repository.CineClubeCatalog
import org.mobyle.domain.repository.SocialNotifier
import org.mobyle.domain.usecase.cineclubes.AdvanceCineClubes
import org.mobyle.domain.usecase.cineclubes.CancelCineClube
import org.mobyle.domain.usecase.cineclubes.CineClubeAccess
import org.mobyle.domain.usecase.cineclubes.CineClubeInput
import org.mobyle.domain.usecase.cineclubes.CineClubeWatchlist
import org.mobyle.domain.usecase.cineclubes.CreateCineClube
import org.mobyle.domain.usecase.cineclubes.GetCineClube
import org.mobyle.domain.usecase.cineclubes.GetCineClubeParticipants
import org.mobyle.domain.usecase.cineclubes.GetCineClubeProgress
import org.mobyle.domain.usecase.cineclubes.JoinCineClube
import org.mobyle.domain.usecase.cineclubes.LeaveCineClube
import org.mobyle.domain.usecase.cineclubes.ListCineClubes
import org.mobyle.domain.usecase.cineclubes.SPOILER_REASON
import org.mobyle.domain.usecase.cineclubes.SessionInput
import org.mobyle.domain.usecase.cineclubes.UpdateCineClube
import org.mobyle.domain.usecase.feed.ActivityPublisher
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

/** CineClubes end to end against Postgres: the data source with the real use cases (B8). */
class CineClubeDataSourceImplTest {

    private lateinit var dataSource: DataSource
    private val cineclubes = CineClubeDataSourceImpl()
    private val shelves = ShelfRepositoryImpl(ShelfDataSourceImpl())
    private val activityRepository = ActivityRepositoryImpl(ActivityDataSourceImpl())
    private val activities = ActivityPublisher(activityRepository)
    private val users = UserDatabaseDataSourceImpl()
    private val hasher = BcryptPasswordHasher(cost = 4)
    private var today = LocalDate(2026, 10, 5)
    private val access = CineClubeAccess(cineclubes) { today }
    private val watchlist = CineClubeWatchlist(cineclubes, shelves)
    private val create = CreateCineClube(cineclubes, access, shelves, hasher, watchlist, activities) { today }
    private val update = UpdateCineClube(cineclubes, access, shelves, hasher) { today }
    private val cancel = CancelCineClube(cineclubes, access)
    private val join = JoinCineClube(cineclubes, access, hasher, watchlist, activities)
    private val leave = LeaveCineClube(cineclubes, access, activities)
    private val list = ListCineClubes(cineclubes) { today }
    private val progress = GetCineClubeProgress(cineclubes, access) { today }
    private val participants = GetCineClubeParticipants(cineclubes, access)
    private val notifier = FakeNotifier()
    private val advance = AdvanceCineClubes(cineclubes, watchlist, notifier) { today }
    private val logSession = LogWatchSession(WatchRepositoryImpl(WatchDataSourceImpl()), shelves, activities)
    private val page = PageRequest(1, 50)

    @BeforeTest
    fun setUp() {
        val name = "cineclubes_${counter.incrementAndGet()}"
        postgres.postgresDatabase.connection.use { it.createStatement().execute("CREATE DATABASE $name") }
        dataSource = postgres.getDatabase("postgres", name)
        DatabaseMigrations.migrate(dataSource)
        Database.connect(dataSource)

        listOf("ana", "bia", "caio").forEach {
            users.save(User(id = it, email = "$it@x.com", username = it, avatar = null, bio = null, createdAt = ""))
        }
        execute(
            """
            INSERT INTO movies (id, tmdb_id, title, "year", poster_path, runtime) VALUES
                (10, 100, 'Rashomon', 1950, '/r.jpg', 88), (11, 101, 'Ran', 1985, '/ran.jpg', 162),
                (12, 102, 'Ikiru', 1952, '/i.jpg', 143);
            """
        )
    }

    // ── Creating (F8.5) ─────────────────────────────────────────────────────

    @Test
    fun `a new CineClube has its owner as participant with the movie on Quero ver`() {
        val cineclube = create("ana", input())

        assertEquals("kurosawa", cineclube.slug)
        assertEquals(CineClubeStatus.ACTIVE, cineclube.status)
        assertFalse(cineclube.isCycle)
        assertEquals("Rashomon", cineclube.currentSession?.movie?.title)
        assertTrue(cineclube.canEdit)
        assertEquals(1, cineclube.totalParticipants)
        assertEquals(SessionProgress.PENDING, cineclube.me?.progress)
        assertEquals("want_to_watch", status("ana", 10))
        assertEquals(
            listOf(ActivityType.CREATED_CINECLUBE),
            activityRepository.findBySubject("ana", ActivitySubject.CINECLUBE, cineclube.id).map { it.type }
        )

        val upcoming = create("bia", input(slug = "futuro", starts = "2026-10-20", ends = "2026-10-26"))
        assertEquals(CineClubeStatus.UPCOMING, upcoming.status)
    }

    @Test
    fun `a taken slug is a conflict and sessions are validated`() {
        create("ana", input())

        assertFailsWith<ConflictException> { create("bia", input(slug = "Kurosawa")) }
        assertFailsWith<ValidationException> { create("bia", input(slug = "passado", starts = "2026-09-01", ends = "2026-09-10")) }
        assertFailsWith<ValidationException> {
            create("bia", input(slug = "debate", debate = "2026-09-30"))
        }
        assertFailsWith<ValidationException> {
            create(
                "bia",
                input(slug = "sobreposto").copy(
                    sessions = listOf(
                        SessionInput(movieId = 10, startsOn = "2026-10-05", endsOn = "2026-10-12"),
                        SessionInput(movieId = 11, startsOn = "2026-10-10", endsOn = "2026-10-17")
                    )
                )
            )
        }
    }

    @Test
    fun `a session can point to a movie known only by its TMDB id`() {
        val cineclube = create(
            "ana",
            input().copy(sessions = listOf(SessionInput(tmdbId = 999, title = "Dersu Uzala", startsOn = "2026-10-05", endsOn = "2026-10-12")))
        )

        assertEquals("Dersu Uzala", cineclube.currentSession?.movie?.title)
        assertEquals(999, cineclube.currentSession?.movie?.tmdbId)
    }

    // ── Joining and leaving (F8.4) ──────────────────────────────────────────

    @Test
    fun `joining asks the password and puts the movie on Quero ver unless it was watched`() {
        create("ana", input(password = "segredo"))
        logSession("caio", 10, null, null, WatchLogInput(watchedOn = "2026-09-01"))

        assertFailsWith<ForbiddenException> { join("bia", "kurosawa", null) }
        assertFailsWith<ForbiddenException> { join("bia", "kurosawa", "errada") }
        join("bia", "kurosawa", "segredo")
        val joined = join("caio", "kurosawa", "segredo")

        assertEquals(3, joined.totalParticipants)
        assertEquals("want_to_watch", status("bia", 10))
        assertEquals("watched", status("caio", 10))
        assertFailsWith<ConflictException> { join("bia", "kurosawa", "segredo") }
    }

    @Test
    fun `leaving takes the participation and the join activity away but keeps the movie`() {
        val cineclube = create("ana", input())
        join("bia", "kurosawa", null)

        leave("bia", "kurosawa")

        assertNull(access.view("bia", "kurosawa").me)
        assertEquals("want_to_watch", status("bia", 10))
        assertTrue(activityRepository.findBySubject("bia", ActivitySubject.CINECLUBE, cineclube.id).isEmpty())
        assertFailsWith<ForbiddenException> { leave("bia", "kurosawa") }
    }

    // ── Progress and participants (F8.3) ────────────────────────────────────

    @Test
    fun `progress comes from the logs within the session period`() {
        create("ana", input())
        join("bia", "kurosawa", null)
        join("caio", "kurosawa", null)
        logSession("bia", 10, null, null, WatchLogInput(watchedOn = "2026-10-06", mode = "percent", percent = 40))
        logSession("caio", 10, null, null, WatchLogInput(watchedOn = "2026-10-07"))
        // Before the period: does not count.
        logSession("ana", 10, null, null, WatchLogInput(watchedOn = "2026-09-01"))

        val all = participants("kurosawa", null, null, completedOnly = false, page).results.associate { it.user.id to it.progress }
        assertEquals(
            mapOf("ana" to SessionProgress.PENDING, "bia" to SessionProgress.WATCHING, "caio" to SessionProgress.COMPLETED),
            all
        )
        assertEquals(listOf("caio"), participants("kurosawa", null, null, completedOnly = true, page).results.map { it.user.id })
        assertEquals(
            listOf("bia"),
            participants("kurosawa", null, SessionProgress.WATCHING, completedOnly = false, page).results.map { it.user.id }
        )
        assertEquals(SessionProgress.COMPLETED, access.view("caio", "kurosawa").me?.progress)
        assertEquals(1, access.view("caio", "kurosawa").me?.completedSessions)
    }

    @Test
    fun `a participant who did not finish the movie gets comments hidden until the debate`() {
        create("ana", input(debate = "2026-10-15"))
        join("bia", "kurosawa", null)
        join("caio", "kurosawa", null)
        logSession("caio", 10, null, null, WatchLogInput(watchedOn = "2026-10-06", comment = "O lenhador mentiu", hasSpoiler = true))
        logSession("ana", 10, null, null, WatchLogInput(watchedOn = "2026-10-06", mode = "percent", percent = 20))

        val forBia = progress("bia", "kurosawa", null, page).results
        val caio = forBia.single { it.user.id == "caio" }
        assertTrue(caio.hidden)
        assertEquals(SPOILER_REASON, caio.hiddenReason)
        assertNull(caio.comment)
        // A log without a comment is shown as is.
        assertFalse(forBia.single { it.user.id == "ana" }.hidden)
        // The author always sees their own comment.
        assertEquals("O lenhador mentiu", progress("caio", "kurosawa", null, page).results.single { it.user.id == "caio" }.comment)
        // Logged out viewers did not finish it either.
        assertTrue(progress(null, "kurosawa", null, page).results.single { it.user.id == "caio" }.hidden)

        // Finishing the movie opens the comments.
        logSession("bia", 10, null, null, WatchLogInput(watchedOn = "2026-10-07"))
        assertEquals("O lenhador mentiu", progress("bia", "kurosawa", null, page).results.single { it.user.id == "caio" }.comment)

        // And so does the debate day, for everyone.
        today = LocalDate(2026, 10, 15)
        assertEquals("O lenhador mentiu", progress(null, "kurosawa", null, page).results.single { it.user.id == "caio" }.comment)
    }

    @Test
    fun `secret logs and logs of people outside the CineClube stay out of the progress`() {
        create("ana", input())
        join("bia", "kurosawa", null)
        logSession("bia", 10, null, null, WatchLogInput(watchedOn = "2026-10-06", visibility = "secret"))
        logSession("caio", 10, null, null, WatchLogInput(watchedOn = "2026-10-06"))

        assertTrue(progress("ana", "kurosawa", null, page).results.isEmpty())
        assertEquals(listOf("bia"), progress("bia", "kurosawa", null, page).results.map { it.user.id })
    }

    // ── Cycles and the scheduler (F8.6, B8) ─────────────────────────────────

    @Test
    fun `a cycle moves to the next movie, notifies the debate and finishes`() {
        val cycle = create("ana", cycleInput())
        join("bia", "ciclo-kurosawa", null)
        assertTrue(cycle.isCycle)
        assertEquals("Rashomon", cycle.currentSession?.movie?.title)
        advance()
        assertEquals(null, status("bia", 11))

        // Debate day of the first movie.
        today = LocalDate(2026, 10, 11)
        val first = advance()
        assertEquals(1, first.debatesNotified)
        assertEquals(setOf("ana", "bia"), notifier.events.filter { it.second is SocialEvent.CineClubeDebate }.map { it.first }.toSet())
        assertEquals(0, advance().debatesNotified)

        // The second session starts: its movie goes to the participants' Quero ver.
        today = LocalDate(2026, 10, 12)
        assertEquals(1, advance().sessionsOpened)
        assertEquals("want_to_watch", status("bia", 11))
        assertEquals("Ran", access.get("ciclo-kurosawa").currentSession?.movie?.title)

        today = LocalDate(2026, 10, 19)
        assertEquals(1, advance().finished)
        assertEquals(CineClubeStatus.FINISHED, access.get("ciclo-kurosawa").status)
    }

    @Test
    fun `an upcoming CineClube starts on its first day`() {
        create("ana", input(slug = "futuro", starts = "2026-10-20", ends = "2026-10-26"))

        today = LocalDate(2026, 10, 20)
        assertEquals(1, advance().started)
        assertEquals(CineClubeStatus.ACTIVE, access.get("futuro").status)
    }

    // ── Editing and cancelling (F8.5) ───────────────────────────────────────

    @Test
    fun `after the start the running sessions stay and new ones come after today`() {
        val cycle = create("ana", cycleInput())
        val first = cycle.sessions.first()

        // Trying to move the running session is ignored; the future one changes movie.
        val edited = update(
            "ana", "ciclo-kurosawa",
            cycleInput().copy(
                title = "Ciclo Kurosawa II",
                sessions = listOf(
                    SessionInput(id = first.id, movieId = 12, startsOn = "2026-10-01", endsOn = "2026-10-03"),
                    SessionInput(id = cycle.sessions[1].id, movieId = 12, startsOn = "2026-10-12", endsOn = "2026-10-18"),
                    SessionInput(movieId = 11, startsOn = "2026-10-19", endsOn = "2026-10-25")
                )
            )
        )

        assertEquals("Ciclo Kurosawa II", edited.title)
        assertEquals(listOf("Rashomon", "Ikiru", "Ran"), edited.sessions.map { it.movie.title })
        assertEquals(first.startsOn, edited.sessions.first().startsOn)
        assertFailsWith<ValidationException> {
            update(
                "ana", "ciclo-kurosawa",
                cycleInput().copy(sessions = listOf(SessionInput(movieId = 11, startsOn = "2026-10-05", endsOn = "2026-10-06")))
            )
        }
        assertFailsWith<ForbiddenException> { update("bia", "ciclo-kurosawa", cycleInput()) }
    }

    @Test
    fun `a cancelled CineClube is read only and leaves the open list`() {
        create("ana", input())
        cancel("ana", "kurosawa")

        assertFailsWith<ConflictException> { join("bia", "kurosawa", null) }
        assertFailsWith<ConflictException> { update("ana", "kurosawa", input()) }
        assertTrue(list(null, CineClubeFilter(), page).results.isEmpty())
        assertEquals(listOf("kurosawa"), list("ana", CineClubeFilter(mine = true), page).results.map { it.slug })
    }

    // ── Listing (F8.2) ──────────────────────────────────────────────────────

    @Test
    fun `the list filters by a movie of any session (movie detail, F12)`() {
        create("ana", input())
        create("bia", cycleInput())

        val withRan = list(null, CineClubeFilter(movieId = 11), page).results.map { it.slug }
        val withRashomon = list(null, CineClubeFilter(movieId = 10), page).results.map { it.slug }.toSet()

        assertEquals(listOf("ciclo-kurosawa"), withRan)
        assertEquals(setOf("kurosawa", "ciclo-kurosawa"), withRashomon)
        assertTrue(list(null, CineClubeFilter(movieId = 12), page).results.isEmpty())
    }

    @Test
    fun `the list filters by text, password and participant`() {
        create("ana", input())
        create("bia", input(slug = "fechado", title = "Clube fechado", password = "segredo"))
        join("caio", "kurosawa", null)

        assertEquals(setOf("kurosawa", "fechado"), list(null, CineClubeFilter(), page).results.map { it.slug }.toSet())
        assertEquals(listOf("kurosawa"), list(null, CineClubeFilter(open = true), page).results.map { it.slug })
        assertEquals(listOf("fechado"), list(null, CineClubeFilter(query = "fech"), page).results.map { it.slug })
        val mine = list("caio", CineClubeFilter(participant = "caio"), page).results.single()
        assertTrue(mine.joined)
        assertEquals("Rashomon", mine.movie?.title)
        assertEquals(1, cineclubes.participationCount("caio"))
    }

    @Test
    fun `the detail brings where to watch the movie`() {
        create("ana", input())
        val detail = runBlocking { GetCineClube(access, FakeCatalog())(null, "kurosawa") }

        assertEquals(listOf("Mubi"), detail.watchProviders.map { it.name })
    }

    private fun input(
        slug: String = "kurosawa",
        title: String = "Kurosawa de outubro",
        starts: String = "2026-10-05",
        ends: String = "2026-10-12",
        debate: String? = null,
        password: String? = null
    ) = CineClubeInput(
        slug = slug,
        title = title,
        password = password,
        sessions = listOf(SessionInput(movieId = 10, startsOn = starts, endsOn = ends, debateOn = debate))
    )

    private fun cycleInput() = CineClubeInput(
        slug = "ciclo-kurosawa",
        title = "Ciclo Kurosawa",
        sessions = listOf(
            SessionInput(movieId = 10, startsOn = "2026-10-05", endsOn = "2026-10-11"),
            SessionInput(movieId = 11, startsOn = "2026-10-12", endsOn = "2026-10-18")
        )
    )

    private fun status(userId: String, movieId: Long): String? = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT um.status FROM user_movies um JOIN users u ON u.id = um.user_id WHERE u.external_id = ? AND um.movie_id = ?"
        ).use { statement ->
            statement.setString(1, userId)
            statement.setLong(2, movieId)
            statement.executeQuery().use { if (it.next()) it.getString(1) else null }
        }
    }

    private class FakeNotifier : SocialNotifier {
        val events = mutableListOf<Pair<String, SocialEvent>>()

        override fun notify(userId: String, event: SocialEvent) {
            events += userId to event
        }
    }

    private class FakeCatalog : CineClubeCatalog {
        override suspend fun watchProviders(tmdbId: Int) = if (tmdbId == 100) listOf(WatchProvider("Mubi")) else emptyList()
    }

    private fun execute(sql: String) = dataSource.connection.use { it.createStatement().execute(sql) }

    companion object {
        private val postgres by lazy { EmbeddedPostgres.start() }
        private val counter = AtomicInteger()
    }
}
