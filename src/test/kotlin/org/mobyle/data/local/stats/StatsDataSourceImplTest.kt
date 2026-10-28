package org.mobyle.data.local.stats

import io.mockk.mockk
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import kotlinx.datetime.LocalDate
import org.jetbrains.exposed.sql.Database
import org.mobyle.data.local.database.DatabaseMigrations
import org.mobyle.data.local.feed.FollowDataSourceImpl
import org.mobyle.data.local.profile.UserProfileDataSourceImpl
import org.mobyle.data.local.shelves.ShelfDataSourceImpl
import org.mobyle.data.local.user.UserDatabaseDataSourceImpl
import org.mobyle.data.repository.ShelfRepositoryImpl
import org.mobyle.data.repository.UserProfileRepositoryImpl
import org.mobyle.domain.model.NotFoundException
import org.mobyle.domain.model.User
import org.mobyle.domain.model.ValidationException
import org.mobyle.domain.model.vocabulary.Emotion
import org.mobyle.domain.model.vocabulary.Rating
import org.mobyle.domain.usecase.shelves.ShelfAccess
import org.mobyle.domain.usecase.stats.GetRetrospective
import org.mobyle.domain.usecase.stats.GetStatsCalendar
import org.mobyle.domain.usecase.stats.GetStatsYears
import org.mobyle.domain.usecase.stats.GetYearStats
import org.mobyle.domain.usecase.stats.GetYearlyGoal
import org.mobyle.domain.usecase.stats.SetYearlyGoal
import org.mobyle.domain.usecase.stats.StatsAccess
import org.mobyle.domain.usecase.users.ProfileAccess
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource
import kotlin.system.measureTimeMillis
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Charts, retrospective and goals against Postgres (B9). */
class StatsDataSourceImplTest {

    private lateinit var dataSource: DataSource
    private val stats = StatsDataSourceImpl()
    private val shelves = ShelfRepositoryImpl(ShelfDataSourceImpl())
    private val shelfAccess = ShelfAccess(shelves)
    private val access = StatsAccess(
        ProfileAccess(UserProfileRepositoryImpl(UserProfileDataSourceImpl(), importRepository = mockk(relaxed = true)), shelfAccess),
        shelfAccess
    )
    private val users = UserDatabaseDataSourceImpl()
    private val follows = FollowDataSourceImpl()
    private val today = LocalDate(2026, 10, 27)
    private val years = GetStatsYears(stats, access)
    private val yearStats = GetYearStats(stats, access) { today }
    private val calendar = GetStatsCalendar(stats, access) { today }
    private val retrospective = GetRetrospective(stats) { today }
    private val getGoal = GetYearlyGoal(stats, access) { today }
    private val setGoal = SetYearlyGoal(stats) { today }

    @BeforeTest
    fun setUp() {
        val name = "stats_${counter.incrementAndGet()}"
        postgres.postgresDatabase.connection.use { it.createStatement().execute("CREATE DATABASE $name") }
        dataSource = postgres.getDatabase("postgres", name)
        DatabaseMigrations.migrate(dataSource)
        Database.connect(dataSource)

        listOf("ana", "bia").forEach {
            users.save(User(id = it, email = "$it@x.com", username = it, avatar = null, bio = null, createdAt = ""))
        }
        execute(
            """
            INSERT INTO movies (id, tmdb_id, title, "year", poster_path, runtime, original_language) VALUES
                (10, 100, 'Rashomon', 1950, '/r.jpg', 88, 'ja'), (11, 101, 'Ran', 1985, '/ran.jpg', 162, 'ja'),
                (12, 102, 'Cidade de Deus', 2002, '/c.jpg', 130, 'pt');
            INSERT INTO genres (id, tmdb_id, "name") VALUES (1, 18, 'Drama'), (2, 80, 'Crime');
            INSERT INTO movie_genres (movie_id, genre_id) VALUES (10, 1), (11, 1), (12, 2);
            INSERT INTO movie_countries (movie_id, country) VALUES (10, 'JP'), (11, 'JP'), (12, 'BR');
            INSERT INTO people (id, tmdb_id, "name") VALUES (1, 5026, 'Akira Kurosawa'), (2, 7450, 'Toshiro Mifune');
            INSERT INTO movie_cast (movie_id, person_id, "role", "position") VALUES
                (10, 1, 'director', 0), (11, 1, 'director', 0), (10, 2, 'actor', 0), (11, 2, 'actor', 0);
            """
        )
        val ana = id("ana")
        execute(
            """
            INSERT INTO watch_logs (user_id, movie_id, watched_on, cycle, mode, minutes_watched, percent, completed, venue,
                                    emotion_ids, visibility, created_at, updated_at) VALUES
                ($ana, 10, '2026-01-10', 1, 'watched', 88, 100, true, 'streaming', '{4}', 'public', now(), now()),
                ($ana, 11, '2026-03-05', 1, 'watched', 162, 100, true, 'cinema', '{4,16}', 'public', now(), now()),
                ($ana, 12, '2026-03-20', 1, 'minutes', 30, 23, false, null, '{}', 'public', now(), now()),
                ($ana, 12, '2026-04-01', 1, 'watched', 130, 100, true, null, '{}', 'secret', now(), now()),
                ($ana, 10, '2025-12-31', 1, 'watched', 88, 100, true, null, '{}', 'public', now(), now());
            INSERT INTO user_movies (user_id, movie_id, status, rating, "source", created_at, updated_at) VALUES
                ($ana, 10, 'watched', 'favorite', 'manual', now(), now()),
                ($ana, 11, 'watched', 'loved', 'manual', now(), now());
            """
        )
    }

    @Test
    fun `the charts count what the viewer can see`() {
        val mine = yearStats("ana", "ana", 2026)
        val public = yearStats(null, "ana", 2026)

        assertEquals(3, mine.movies)
        assertEquals(2, public.movies)
        assertEquals(88 + 162 + 30, public.minutes)
        assertEquals(listOf("18" to 2), public.genres.map { it.key to it.movies })
        assertEquals("Akira Kurosawa", public.directors.single().label)
        assertEquals(listOf("JP"), public.countries.map { it.key })
        assertEquals("moved", public.emotions.first().key)
        assertEquals(listOf("favorite", "loved"), public.ratings.map { it.key })
        assertEquals(listOf(2026, 2025), years.invoke(null, "ana").years)
        assertEquals(3, calendar(null, "ana", 2026).size)
        assertFailsWith<ValidationException> { yearStats(null, "ana", 1800) }
    }

    @Test
    fun `ratings stay out when the Ja assisti is secret`() {
        execute("UPDATE shelves SET visibility = 'secret' WHERE system_type = 'watched'")
        shelves.ensureSystemShelves("ana")
        execute("UPDATE shelves SET visibility = 'secret' WHERE system_type = 'watched'")

        assertTrue(yearStats(null, "ana", 2026).ratings.isEmpty())
        assertEquals(2, yearStats("ana", "ana", 2026).ratings.size)
    }

    @Test
    fun `mutuals see the mutual sessions`() {
        execute("UPDATE watch_logs SET visibility = 'mutual' WHERE movie_id = 12 AND completed")
        follows.follow("ana", "bia")
        follows.follow("bia", "ana")

        assertEquals(3, yearStats("bia", "ana", 2026).movies)
    }

    @Test
    fun `the retrospective tells the year of the owner`() {
        setGoal("ana", 2026, 10)

        val story = retrospective("ana", 2026)

        assertEquals(3, story.movies)
        assertEquals("Rashomon", story.favorite?.title)
        assertEquals(Rating.FAVORITE, story.favoriteRating)
        assertEquals("Akira Kurosawa", story.topDirectors.first().name)
        assertEquals(2, story.topDirectors.first().movies)
        assertEquals("Toshiro Mifune", story.topActors.first().name)
        assertEquals(Emotion.MOVED, story.emotion)
        assertEquals(3, story.bestMonth?.month)
        assertEquals(3, story.goal?.watched)
        assertEquals(30, story.goal?.percent)
        assertEquals(listOf(2026, 2025), retrospective.years("ana").map { it.year })
        assertFailsWith<NotFoundException> { retrospective("bia", 2026) }
    }

    @Test
    fun `goals are for this year or the next and show the progress`() {
        val goal = setGoal("ana", 2026, 2)

        assertTrue(goal.reached)
        assertEquals(2, getGoal(null, "ana", 2026).watched)
        assertEquals(3, getGoal("ana", "ana", 2026).watched)
        assertEquals(50, setGoal("ana", 2026, 6).percent)
        assertFailsWith<ValidationException> { setGoal("ana", 2025, 10) }
        assertFailsWith<ValidationException> { setGoal("ana", 2026, 0) }
        setGoal.remove("ana", 2026)
        assertNull(stats.goal("ana", 2026))
        assertFailsWith<NotFoundException> { getGoal(null, "ana", 2026) }
    }

    @Test
    fun `a year with 2000 sessions answers quickly`() {
        val bia = id("bia")
        execute(
            """
            INSERT INTO movies (id, tmdb_id, title, "year", runtime)
            SELECT 1000 + g, 10000 + g, 'Filme ' || g, 1950 + (g % 70), 100 FROM generate_series(1, 500) g;
            INSERT INTO movie_genres (movie_id, genre_id) SELECT 1000 + g, 1 + (g % 2) FROM generate_series(1, 500) g;
            INSERT INTO watch_logs (user_id, movie_id, watched_on, cycle, mode, minutes_watched, percent, completed,
                                    emotion_ids, visibility, created_at, updated_at)
            SELECT $bia, 1001 + (g % 500), DATE '2026-01-01' + (g % 300), 1, 'watched', 100, 100, true, '{4}', 'public', now(), now()
            FROM generate_series(1, 2000) g;
            """
        )
        yearStats(null, "bia", 2026)

        val elapsed = measureTimeMillis { yearStats(null, "bia", 2026) }

        assertEquals(500, yearStats(null, "bia", 2026).movies)
        // The target is 300 ms in production; the embedded database leaves room.
        assertTrue(elapsed < 1500, "took $elapsed ms")
    }

    private fun id(user: String): Long = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT id FROM users WHERE external_id = ?").use { statement ->
            statement.setString(1, user)
            statement.executeQuery().use { it.next(); it.getLong(1) }
        }
    }

    private fun execute(sql: String) = dataSource.connection.use { it.createStatement().execute(sql) }

    companion object {
        private val postgres by lazy { EmbeddedPostgres.start() }
        private val counter = AtomicInteger()
    }
}
