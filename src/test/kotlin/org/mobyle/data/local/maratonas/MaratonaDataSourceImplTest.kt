package org.mobyle.data.local.maratonas

import io.mockk.mockk
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.Database
import org.mobyle.data.local.database.DatabaseMigrations
import org.mobyle.data.local.feed.ActivityDataSourceImpl
import org.mobyle.data.local.movies.MovieCatalogDataSourceImpl
import org.mobyle.data.local.shelves.ShelfDataSourceImpl
import org.mobyle.data.local.user.UserDatabaseDataSourceImpl
import org.mobyle.data.local.watch.WatchDataSourceImpl
import org.mobyle.data.repository.ActivityRepositoryImpl
import org.mobyle.data.repository.MaratonaCatalogImpl
import org.mobyle.data.repository.ShelfRepositoryImpl
import org.mobyle.data.repository.WatchRepositoryImpl
import org.mobyle.data.service.BcryptPasswordHasher
import org.mobyle.domain.model.ChallengeCriteria
import org.mobyle.domain.model.ConflictException
import org.mobyle.domain.model.ForbiddenException
import org.mobyle.domain.model.MaratonaFilter
import org.mobyle.domain.model.Movie
import org.mobyle.domain.model.MovieFacts
import org.mobyle.domain.model.Page
import org.mobyle.domain.model.PageRequest
import org.mobyle.domain.model.User
import org.mobyle.domain.model.vocabulary.ActivityType
import org.mobyle.domain.model.vocabulary.ChallengeLinkSource
import org.mobyle.domain.model.vocabulary.MaratonaStatus
import org.mobyle.domain.repository.MaratonaCatalog
import org.mobyle.domain.usecase.feed.ActivityPublisher
import org.mobyle.domain.usecase.maratonas.AdvanceMaratonas
import org.mobyle.domain.usecase.maratonas.CategoryInput
import org.mobyle.domain.usecase.maratonas.ChallengeInput
import org.mobyle.domain.usecase.maratonas.CreateMaratona
import org.mobyle.domain.usecase.maratonas.CreateMaratonaFromTemplate
import org.mobyle.domain.usecase.maratonas.FromTemplateInput
import org.mobyle.domain.usecase.maratonas.GetMaratonaChallenges
import org.mobyle.domain.usecase.maratonas.GetMaratonaProgress
import org.mobyle.domain.usecase.maratonas.JoinMaratona
import org.mobyle.domain.usecase.maratonas.LeaveMaratona
import org.mobyle.domain.usecase.maratonas.LevelInput
import org.mobyle.domain.usecase.maratonas.MaratonaAccess
import org.mobyle.domain.usecase.maratonas.MaratonaAutoLinker
import org.mobyle.domain.usecase.maratonas.MaratonaInput
import org.mobyle.domain.usecase.maratonas.SetChallengeLink
import org.mobyle.domain.usecase.maratonas.TeamInput
import org.mobyle.domain.usecase.maratonas.UpdateMaratona
import org.mobyle.domain.usecase.watch.LogWatchSession
import org.mobyle.domain.usecase.watch.WatchLogInput
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Maratonas end to end against Postgres: the data source with the real use cases (B7). */
class MaratonaDataSourceImplTest {

    private lateinit var dataSource: DataSource
    private val maratonas = MaratonaDataSourceImpl()
    private val shelfData = ShelfDataSourceImpl()
    private val shelves = ShelfRepositoryImpl(shelfData)
    private val activityData = ActivityDataSourceImpl()
    private val activities = ActivityPublisher(ActivityRepositoryImpl(activityData))
    private val users = UserDatabaseDataSourceImpl()
    private val hasher = BcryptPasswordHasher(cost = 4)
    private val access = MaratonaAccess(maratonas)
    private var today = LocalDate(2026, 10, 1)
    private val create = CreateMaratona(maratonas, access, hasher, activities) { today }
    private val update = UpdateMaratona(maratonas, access, hasher) { today }
    private val join = JoinMaratona(maratonas, access, shelves, hasher, activities)
    private val leave = LeaveMaratona(maratonas, access, shelves, activities)
    private val challenges = GetMaratonaChallenges(maratonas, access)
    private val link = SetChallengeLink(maratonas, access, shelves, activities)
    private val progress = GetMaratonaProgress(access, ActivityRepositoryImpl(activityData))
    private val advance = AdvanceMaratonas(maratonas, activities) { today }
    private val catalog = FakeCatalog()
    private val logSession = LogWatchSession(
        WatchRepositoryImpl(WatchDataSourceImpl()), shelves, activities, MaratonaAutoLinker(maratonas, catalog, activities)
    )
    private val page = PageRequest(1, 50)

    @BeforeTest
    fun setUp() {
        val name = "maratonas_${counter.incrementAndGet()}"
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
                (10, 100, 'Suspiria', 1977, '/s.jpg', 99), (11, 101, 'Halloween', 1978, '/h.jpg', 91),
                (12, 102, 'Cidade de Deus', 2002, '/c.jpg', 130);
            """
        )
        catalog.facts[10] = facts(10, genres = setOf(27), year = 1977, countries = setOf("IT"))
        catalog.facts[11] = facts(11, genres = setOf(27), year = 1978, countries = setOf("US"))
        catalog.facts[12] = facts(12, genres = setOf(80, 18), year = 2002, countries = setOf("BR"))
    }

    // ── Creating and editing (F7.7) ─────────────────────────────────────────

    @Test
    fun `a new maratona keeps its parts, gets a level for every challenge and its status from the period`() {
        val maratona = create("ana", input())

        assertEquals("terror-70", maratona.slug)
        assertEquals(MaratonaStatus.ACTIVE, maratona.status)
        assertEquals(listOf("Terror", "Brasil"), maratona.categories.map { it.name })
        assertEquals(3, maratona.totalChallenges)
        assertEquals(listOf(3), maratona.levels.map { it.minChallenges })
        assertTrue(maratona.canEdit)
        assertEquals(
            listOf(ActivityType.CREATED_MARATONA),
            progress(null, "terror-70", null).results.map { it.type }
        )

        val upcoming = create("ana", input(slug = "outubro", startsOn = "2026-10-20"))
        assertEquals(MaratonaStatus.UPCOMING, upcoming.status)
    }

    @Test
    fun `a taken slug is a conflict`() {
        create("ana", input())

        assertFailsWith<ConflictException> { create("bia", input(slug = "Terror-70")) }
    }

    @Test
    fun `before the start the structure is replaced and ids that stay are kept`() {
        val maratona = create("ana", input(startsOn = "2026-10-20"))
        val terror = maratona.categories.first()

        val edited = update(
            "ana", "terror-70",
            input(startsOn = "2026-10-20").copy(
                title = "Terror dos 70",
                categories = listOf(
                    CategoryInput(
                        id = terror.id, name = "Terror clássico",
                        challenges = listOf(
                            ChallengeInput(id = terror.challenges[0].id, description = "Um terror dos anos 70", criteria = horror70s),
                            ChallengeInput(description = "Um slasher"),
                            ChallengeInput(description = "Um giallo")
                        )
                    )
                ),
                levels = listOf(LevelInput(name = "Iniciante", minChallenges = 1), LevelInput(name = "Mestre", minChallenges = 3))
            )
        )

        assertEquals("Terror dos 70", edited.title)
        assertEquals(listOf(terror.id), edited.categories.map { it.id })
        assertEquals(terror.challenges[0].id, edited.categories[0].challenges[0].id)
        assertEquals(listOf("Um terror dos anos 70", "Um slasher", "Um giallo"), edited.categories[0].challenges.map { it.description })
        assertEquals(listOf("Iniciante", "Mestre"), edited.levels.map { it.name })
    }

    @Test
    fun `after the start only the texts change`() {
        val maratona = create("ana", input())

        val edited = update("ana", "terror-70", input().copy(title = "Novo título", categories = listOf(
            CategoryInput(name = "Outra", challenges = List(3) { ChallengeInput(description = "Livre $it") })
        )))

        assertEquals("Novo título", edited.title)
        assertEquals(maratona.categories.map { it.id }, edited.categories.map { it.id })
        assertFailsWith<ForbiddenException> { update("bia", "terror-70", input()) }
    }

    // ── Joining and leaving (F7.4) ──────────────────────────────────────────

    @Test
    fun `joining asks for the password and creates the queue shelf, leaving deletes it`() {
        create("ana", input().copy(password = "pipoca"))

        assertFailsWith<ForbiddenException> { join("bia", "terror-70", null, null, "errada") }
        val joined = join("bia", "terror-70", null, null, "pipoca")
        val me = assertNotNull(joined.me)
        val queue = assertNotNull(shelves.getShelf(me.queueShelfId!!))
        assertEquals("Fila: Terror 70", queue.name)
        assertEquals(1, joined.totalParticipants)
        assertFailsWith<ConflictException> { join("bia", "terror-70", null, null, "pipoca") }

        leave("bia", "terror-70")

        assertNull(shelves.getShelf(queue.id))
        assertNull(access.view("bia", "terror-70").me)
    }

    // ── Completing challenges (F7.5) ────────────────────────────────────────

    @Test
    fun `logging Suspiria during the maratona completes the horror from the 70s by itself`() {
        create("ana", input())
        join("bia", "terror-70", null, null, null)

        logSession("bia", 10, null, null, WatchLogInput(watchedOn = "2026-10-05"))

        val done = challenges("bia", "terror-70", null).flatMap { it.challenges }.filter { it.completed }
        assertEquals(listOf("Um terror dos anos 70"), done.map { it.description })
        assertEquals(ChallengeLinkSource.AUTO, done.single().links.single().source)
        assertEquals(
            listOf(ActivityType.FINISHED_CHALLENGE, ActivityType.JOINED_MARATONA, ActivityType.CREATED_MARATONA),
            progress(null, "terror-70", null).results.map { it.type }
        )

        // A second match ties the movie, but the challenge was already done: no second activity.
        logSession("bia", 11, null, null, WatchLogInput(watchedOn = "2026-10-06"))
        val links = challenges("bia", "terror-70", null).flatMap { it.challenges }.single { it.completed }.links
        assertEquals(listOf(10L, 11L), links.map { it.movieId })
        assertEquals(1, progress(null, "terror-70", null).results.count { it.type == ActivityType.FINISHED_CHALLENGE })
    }

    @Test
    fun `a session from before the maratona does not count`() {
        create("ana", input())
        join("bia", "terror-70", null, null, null)

        logSession("bia", 10, null, null, WatchLogInput(watchedOn = "2026-09-20"))

        assertTrue(challenges("bia", "terror-70", null).flatMap { it.challenges }.none { it.completed })
    }

    @Test
    fun `a free challenge is marked by hand and unmarking the last movie undoes it`() {
        create("ana", input())
        join("bia", "terror-70", null, null, null)
        val free = access.get("terror-70").challenges.single { it.criteria == null && it.description == "Um filme brasileiro" }

        val marked = link("bia", "terror-70", free.id, 12, linked = true)
        assertTrue(marked.completed)
        assertEquals(1, access.view("bia", "terror-70").me!!.completed)

        val unmarked = link("bia", "terror-70", free.id, 12, linked = false)
        assertFalse(unmarked.completed)
        assertTrue(progress(null, "terror-70", null).results.none { it.type == ActivityType.FINISHED_CHALLENGE })
        assertFailsWith<ForbiddenException> { link("caio", "terror-70", free.id, 12, linked = true) }
    }

    // ── Ranking (F7.6) ──────────────────────────────────────────────────────

    @Test
    fun `the ranking puts more challenges first and gives a tie the same position`() {
        val maratona = create("ana", input().copy(teams = listOf(TeamInput(name = "Vermelho"), TeamInput(name = "Azul"))))
        val (red, blue) = maratona.teams
        join("ana", "terror-70", null, red.id, null)
        join("bia", "terror-70", null, blue.id, null)
        join("caio", "terror-70", null, blue.id, null)
        val (first, second) = maratona.challenges
        link("ana", "terror-70", first.id, 10, linked = true)
        link("ana", "terror-70", second.id, 11, linked = true)
        link("bia", "terror-70", first.id, 10, linked = true)
        link("caio", "terror-70", first.id, 10, linked = true)
        execute("UPDATE challenge_links SET completed_at = '2026-10-05 12:00:00'")

        val ranking = maratonas.ranking(maratona.id, page).results
        assertEquals(listOf("ana" to 1, "bia" to 2, "caio" to 2), ranking.map { it.user.id to it.position })
        assertEquals(listOf(2, 1, 1), ranking.map { it.completed })

        val teams = maratonas.teamRanking(maratona.id)
        assertEquals(listOf("Vermelho" to 2, "Azul" to 2), teams.map { it.team.name to it.completed })
        assertEquals(listOf(1, 1), teams.map { it.position })
    }

    // ── Period (F7.8) ───────────────────────────────────────────────────────

    @Test
    fun `the scheduler starts and finishes maratonas and celebrates who reached their level`() {
        create("ana", input(slug = "outubro", startsOn = "2026-10-10", endsOn = "2026-10-20").copy(
            levels = listOf(LevelInput(name = "Iniciante", minChallenges = 1), LevelInput(name = "Mestre", minChallenges = 3))
        ))
        val easy = access.get("outubro").levels.first()
        join("bia", "outubro", easy.id, null, null)
        join("caio", "outubro", null, null, null)

        today = LocalDate(2026, 10, 10)
        assertEquals(1 to 0, advance())
        val challenge = access.get("outubro").challenges.first()
        link("bia", "outubro", challenge.id, 10, linked = true)

        today = LocalDate(2026, 10, 21)
        assertEquals(0 to 1, advance())

        assertEquals(MaratonaStatus.FINISHED, access.get("outubro").status)
        val finished = progress(null, "outubro", null).results.filter { it.type == ActivityType.FINISHED_MARATONA }
        assertEquals(listOf("bia"), finished.map { it.actor.id })
        assertEquals(0 to 0, advance())
    }

    // ── Listing (F7.2) ──────────────────────────────────────────────────────

    @Test
    fun `the list filters by title, password, status and owner`() {
        today = LocalDate(2026, 9, 1)
        create("ana", input(slug = "antiga", startsOn = "2026-09-01", endsOn = "2026-09-30").copy(title = "Setembro"))
        create("ana", input())
        create("bia", input(slug = "cinema-novo").copy(title = "Cinema Novo", password = "glauber"))
        today = LocalDate(2026, 10, 1)
        assertEquals(2 to 1, advance())
        join("caio", "cinema-novo", null, null, "glauber")

        fun slugs(filter: MaratonaFilter, viewer: String? = null) = maratonas.list(viewer, filter, page).results.map { it.slug }.toSet()

        assertEquals(setOf("terror-70", "cinema-novo"), slugs(MaratonaFilter()))
        assertEquals(setOf("terror-70", "cinema-novo", "antiga"), slugs(MaratonaFilter(includeFinished = true)))
        assertEquals(setOf("terror-70"), slugs(MaratonaFilter(open = true)))
        assertEquals(setOf("cinema-novo"), slugs(MaratonaFilter(query = "novo")))
        assertEquals(setOf("terror-70", "antiga"), slugs(MaratonaFilter(mine = true), "ana"))
        assertEquals(setOf("cinema-novo"), slugs(MaratonaFilter(participant = "caio")))
        val row = maratonas.list("caio", MaratonaFilter(query = "novo"), page).results.single()
        assertTrue(row.joined)
        assertTrue(row.hasPassword)
        assertEquals(1, row.totalParticipants)
    }

    @Test
    fun `the movie filter keeps the maratonas where the movie completed a challenge, finished included`() {
        create("ana", input(slug = "antiga", startsOn = "2026-10-01", endsOn = "2026-10-10"))
        create("ana", input())
        create("bia", input(slug = "sem-filme"))
        join("bia", "antiga", null, null, null)
        join("caio", "terror-70", null, null, null)
        join("caio", "sem-filme", null, null, null)
        link("bia", "antiga", access.get("antiga").challenges.first().id, 10, linked = true)
        link("caio", "terror-70", access.get("terror-70").challenges.first().id, 10, linked = true)
        link("caio", "sem-filme", access.get("sem-filme").challenges.first().id, 11, linked = true)
        today = LocalDate(2026, 10, 11)
        assertEquals(0 to 1, advance())

        fun slugs(movieId: Long) =
            maratonas.list(null, MaratonaFilter(movieId = movieId, includeFinished = true), page).results.map { it.slug }

        assertEquals(listOf("terror-70", "antiga"), slugs(10))
        assertEquals(listOf("sem-filme"), slugs(11))
        assertEquals(emptyList(), slugs(12))
        assertEquals(listOf("terror-70"), maratonas.list(null, MaratonaFilter(movieId = 10), page).results.map { it.slug })
        execute("UPDATE maratonas SET status = 'cancelled' WHERE slug = 'terror-70'")
        assertEquals(listOf("antiga"), slugs(10))
        assertTrue(maratonas.list("bia", MaratonaFilter(movieId = 10, includeFinished = true), page).results.single().joined)
    }

    @Test
    fun `a template becomes a maratona with its challenges`() {
        val maratona = CreateMaratonaFromTemplate(create)("ana", "decada-por-decada", FromTemplateInput("decadas", "2026-10-01"))

        assertEquals("Década por década", maratona.title)
        assertEquals(11, maratona.totalChallenges)
        assertEquals(LocalDate(2027, 1, 28), maratona.endsOn)
        assertEquals("decada-por-decada", maratona.templateId)
    }

    // ── Movie metadata ──────────────────────────────────────────────────────

    @Test
    fun `the catalog reads the facts the enrichment saved`() {
        execute(
            """
            UPDATE movies SET needs_enrichment = FALSE WHERE id = 10;
            INSERT INTO genres (id, tmdb_id, "name") VALUES (1, 27, 'Terror');
            INSERT INTO movie_genres (movie_id, genre_id) VALUES (10, 1);
            INSERT INTO people (id, tmdb_id, "name") VALUES (1, 4955, 'Dario Argento'), (2, 10, 'Jessica Harper');
            INSERT INTO movie_cast (movie_id, person_id, "role", "position") VALUES (10, 1, 'director', 0), (10, 2, 'actor', 0);
            """
        )
        MovieCatalogDataSourceImpl().saveMovieFacts(100, "it", listOf("it", "DE"), listOf(900 to "witch", 900 to "witch"))

        val facts = assertNotNull(MaratonaCatalogImpl(mockk(), mockk(), mockk()).facts(10))

        assertEquals(setOf(27), facts.genreIds)
        assertEquals(1977, facts.year)
        assertEquals(setOf("IT", "DE"), facts.countries)
        assertEquals("it", facts.originalLanguage)
        assertEquals(setOf(4955), facts.directorIds)
        assertEquals(setOf(10), facts.castIds)
        assertEquals(setOf(900), facts.keywordIds)
        assertEquals(99, facts.runtime)
    }

    private val horror70s = Json.decodeFromString(
        ChallengeCriteria.serializer(),
        """{"all":[{"field":"genre","op":"in","value":[27]},{"field":"decade","op":"in","value":[1970]}]}"""
    )

    private fun input(slug: String = "terror-70", startsOn: String = "2026-10-01", endsOn: String = "2026-10-31") = MaratonaInput(
        slug = slug,
        title = "Terror 70",
        startsOn = startsOn,
        endsOn = endsOn,
        categories = listOf(
            CategoryInput(
                name = "Terror",
                challenges = listOf(
                    ChallengeInput(description = "Um terror dos anos 70", criteria = horror70s),
                    ChallengeInput(description = "Um terror de 2 horas ou mais", criteria = Json.decodeFromString(
                        ChallengeCriteria.serializer(),
                        """{"all":[{"field":"genre","op":"in","value":[27]},{"field":"runtime_min","op":"gte","value":120}]}"""
                    ))
                )
            ),
            CategoryInput(name = "Brasil", challenges = listOf(ChallengeInput(description = "Um filme brasileiro")))
        )
    )

    private fun facts(id: Long, genres: Set<Int>, year: Int, countries: Set<String>) = MovieFacts(
        movieId = id, genreIds = genres, year = year, countries = countries, originalLanguage = null,
        directorIds = emptySet(), castIds = emptySet(), runtime = null, keywordIds = emptySet(), voteAverage = null
    )

    private class FakeCatalog : MaratonaCatalog {
        val facts = mutableMapOf<Long, MovieFacts>()

        override fun facts(movieId: Long): MovieFacts? = facts[movieId]

        override fun suggestions(criteria: ChallengeCriteria, page: Int): Page<Movie> = Page(emptyList(), page, 0, 0, false)
    }

    private fun execute(sql: String) = dataSource.connection.use { it.createStatement().execute(sql) }

    companion object {
        private val postgres by lazy { EmbeddedPostgres.start() }
        private val counter = AtomicInteger()
    }
}
