package org.mobyle.data.local.watch

import io.mockk.mockk
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import kotlinx.datetime.LocalDate
import org.jetbrains.exposed.sql.Database
import org.mobyle.data.local.comments.CommentThreadDataSourceImpl
import org.mobyle.data.local.database.DatabaseMigrations
import org.mobyle.data.local.shelves.ShelfDataSourceImpl
import org.mobyle.data.local.user.UserDatabaseDataSourceImpl
import org.mobyle.domain.model.CommentTarget
import org.mobyle.domain.model.EmotionCount
import org.mobyle.domain.model.Gif
import org.mobyle.domain.model.PageRequest
import org.mobyle.domain.model.ShelfDraft
import org.mobyle.domain.model.User
import org.mobyle.domain.model.WatchLogDraft
import org.mobyle.domain.model.vocabulary.Emotion
import org.mobyle.domain.model.vocabulary.Rating
import org.mobyle.domain.model.vocabulary.SystemShelfType
import org.mobyle.domain.model.vocabulary.Visibility
import org.mobyle.domain.model.vocabulary.WatchMode
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource
import org.mobyle.domain.model.MovieListState
import org.mobyle.domain.model.vocabulary.WatchStatus
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WatchDataSourceImplTest {

    private lateinit var dataSource: DataSource
    private val watch = WatchDataSourceImpl()
    private val shelves = ShelfDataSourceImpl()
    private val comments = CommentThreadDataSourceImpl()
    private val users = UserDatabaseDataSourceImpl()
    private val page = PageRequest(1, 50)
    private val everyone = Visibility.entries.toSet()

    @BeforeTest
    fun setUp() {
        // One embedded server per class, one fresh database per test.
        val name = "watch_${counter.incrementAndGet()}"
        postgres.postgresDatabase.connection.use { it.createStatement().execute("CREATE DATABASE $name") }
        dataSource = postgres.getDatabase("postgres", name)
        DatabaseMigrations.migrate(dataSource)
        Database.connect(dataSource)

        users.save(User(id = "ana", email = "ana@x.com", username = "ana", avatar = null, bio = null, createdAt = ""))
        execute("INSERT INTO movies (id, tmdb_id, title, runtime) VALUES (10, 100, 'Casablanca', 100), (11, 101, 'Unknown', NULL);")
    }

    @Test
    fun `a completed session moves the movie to Ja assisti and off Quero ver`() {
        shelves.addMovie(system(SystemShelfType.WATCHLIST), 10)
        watch.setWatchingNow("ana", 10)

        watch.createLog("ana", 10, draft(mode = WatchMode.MINUTES, minutes = 40, percent = 40))
        assertEquals(listOf(10L), movieIds(SystemShelfType.WATCHLIST))
        assertEquals(40, watch.getUserMovie("ana", 10)?.progressPercent)

        watch.createLog("ana", 10, draft())

        assertEquals(listOf(10L), movieIds(SystemShelfType.WATCHED))
        assertEquals(emptyList(), movieIds(SystemShelfType.WATCHLIST))
        val userMovie = watch.getUserMovie("ana", 10)!!
        assertEquals(100, userMovie.progressPercent)
        assertEquals(100, userMovie.progressMinutes)
        assertNull(watch.watchingNow("ana"))
    }

    @Test
    fun `deleting a session recomputes the progress but keeps the movie on Ja assisti`() {
        watch.createLog("ana", 10, draft(mode = WatchMode.MINUTES, minutes = 30, percent = 30))
        val completed = watch.createLog("ana", 10, draft())

        watch.deleteLog(completed.id)

        val userMovie = watch.getUserMovie("ana", 10)!!
        assertEquals(30, userMovie.progressPercent)
        assertEquals(30, userMovie.progressMinutes)
        assertEquals(listOf(10L), movieIds(SystemShelfType.WATCHED))
    }

    @Test
    fun `rewatch opens a new cycle and the summary adds up every cycle`() {
        watch.createLog("ana", 10, draft(emotions = listOf(Emotion.JOY, Emotion.FEAR)))
        val progress = watch.startRewatch("ana", 10)
        watch.createLog("ana", 10, draft(mode = WatchMode.MINUTES, minutes = 25, percent = 25, emotions = listOf(Emotion.JOY)))

        assertEquals(2, progress.cycle)
        val userMovie = watch.getUserMovie("ana", 10)!!
        assertEquals(1, userMovie.rewatches)
        assertEquals(25, userMovie.progressPercent)
        val logs = watch.getLogs("ana", 10, everyone, page).results
        assertEquals(listOf(2, 1), logs.map { it.cycle }.sortedDescending())

        val summary = watch.summary("ana", 10, everyone)
        assertEquals(125, summary.totalMinutes)
        assertEquals(2, summary.sessions)
        assertEquals(listOf(Emotion.JOY, Emotion.FEAR), summary.topEmotions)
    }

    @Test
    fun `secret sessions stay out of what others see`() {
        watch.createLog("ana", 10, draft())
        watch.createLog("ana", 10, draft(visibility = Visibility.SECRET))

        assertEquals(1, watch.getLogs("ana", 10, setOf(Visibility.PUBLIC), page).totalResults)
        assertEquals(1, watch.summary("ana", 10, setOf(Visibility.PUBLIC)).sessions)
        assertEquals(2, watch.summary("ana", 10, everyone).sessions)
    }

    @Test
    fun `rating is stored as a label and does not touch the shelves`() {
        val rating = watch.setRating("ana", 10, Rating.LIKED, "Bom", hasSpoiler = true)

        assertEquals(Rating.LIKED, rating.rating)
        assertEquals(Rating.LIKED, watch.getUserMovie("ana", 10)?.rating)
        assertEquals("liked", query("SELECT rating FROM user_movies WHERE movie_id = 10"))
        // A rating alone does not mark the movie as watched.
        assertEquals(emptyList(), movieIds(SystemShelfType.WATCHED))

        watch.removeRating("ana", 10)
        assertNull(watch.getUserMovie("ana", 10)?.rating)
    }

    @Test
    fun `list states bring status and label only for the movies that have any`() {
        watch.createLog("ana", 10, draft())
        watch.setRating("ana", 11, Rating.LOVED, null, false)

        val states = watch.listStates("ana", listOf(10, 11, 99, 0))

        assertEquals(MovieListState(status = WatchStatus.WATCHED, rating = null), states[10])
        assertEquals(MovieListState(status = null, rating = Rating.LOVED), states[11])
        assertEquals(setOf(10L, 11L), states.keys)
        assertEquals(emptyMap(), watch.listStates("nobody", listOf(10)))
    }

    @Test
    fun `the database accepts only the seven labels`() {
        watch.setRating("ana", 10, Rating.ON_HOLD, null, false)

        val error = runCatching { execute("UPDATE user_movies SET rating = '4.5' WHERE movie_id = 10;") }.exceptionOrNull()
        assertTrue(error != null)
        assertEquals("on_hold", query("SELECT rating FROM user_movies WHERE movie_id = 10"))
    }

    @Test
    fun `recent picks`() {
        val gif = Gif("g1", "https://media.tenor.com/g1.gif")
        watch.createLog("ana", 10, draft(emotions = listOf(Emotion.FEAR), gif = gif))
        watch.createLog("ana", 10, draft(emotions = listOf(Emotion.JOY, Emotion.FEAR), gif = gif))

        assertEquals(listOf(Emotion.JOY, Emotion.FEAR), watch.recentEmotions("ana", 12))
        assertEquals(listOf(gif), watch.recentGifs("ana", 12))
    }

    @Test
    fun `a session keeps the label`() {
        watch.setRating("ana", 11, Rating.ON_HOLD, null, false)

        watch.createLog("ana", 11, draft(mode = WatchMode.MINUTES, minutes = 50, percent = null))

        assertEquals(Rating.ON_HOLD, watch.getUserMovie("ana", 11)?.rating)
    }

    @Test
    fun `comments are listed oldest first and deleted ones disappear`() {
        val log = watch.createLog("ana", 10, draft())
        val first = comments.createComment("ana", CommentTarget.WATCH_LOG, log.id, null, "Que filme", hasSpoiler = false)
        comments.createComment("ana", CommentTarget.WATCH_LOG, log.id, first.id, "Concordo", hasSpoiler = true)

        assertEquals(listOf("Que filme", "Concordo"), comments.getComments(CommentTarget.WATCH_LOG, log.id, page).results.map { it.body })

        comments.deleteComment(first.id)
        assertNull(comments.getComment(first.id))
        assertEquals(1, comments.getComments(CommentTarget.WATCH_LOG, log.id, page).totalResults)

        val userMovieId = watch.setRating("ana", 10, Rating.LOVED, null, false).id
        assertEquals("ana", comments.ratingOwner(userMovieId))
    }

    @Test
    fun `the diary lists the sessions of every movie newest first, with the movie, within the visibilities`() {
        watch.createLog("ana", 10, draft().copy(watchedOn = LocalDate(2026, 9, 1)))
        watch.createLog("ana", 11, draft(visibility = Visibility.SECRET).copy(watchedOn = LocalDate(2026, 10, 2)))
        watch.createLog("ana", 11, draft().copy(watchedOn = LocalDate(2026, 10, 3)))

        val forOwner = watch.getUserLogs("ana", everyone, page)
        assertEquals(listOf(11L, 11L, 10L), forOwner.results.map { it.movie.movieId })
        assertEquals("Casablanca", forOwner.results.last().movie.title)

        val forOthers = watch.getUserLogs("ana", setOf(Visibility.PUBLIC), page)
        assertEquals(2, forOthers.totalResults)
        assertTrue(forOthers.results.none { it.log.visibility == Visibility.SECRET })
    }

    @Test
    fun `the community block counts only public Ja assisti, Quero ver, custom shelves and sessions`() {
        listOf("bia", "caio").forEach {
            users.save(User(id = it, email = "$it@x.com", username = it, avatar = null, bio = null, createdAt = ""))
        }
        watch.createLog("ana", 10, draft(emotions = listOf(Emotion.JOY, Emotion.FEAR)))
        watch.setRating("ana", 10, Rating.FAVORITE, null, false)
        watch.createLog("bia", 10, draft(emotions = listOf(Emotion.JOY)))
        watch.setRating("bia", 10, Rating.LIKED, null, false)
        watch.createLog("caio", 10, draft(emotions = listOf(Emotion.FEAR), visibility = Visibility.SECRET))
        watch.setRating("caio", 10, Rating.NOT_FOR_ME, null, false)
        execute(
            "UPDATE shelves SET visibility = 'secret' WHERE system_type = 'watched' " +
                "AND user_id = (SELECT id FROM users WHERE external_id = 'caio');"
        )
        shelves.addMovie(shelves.getUserShelves("caio", everyone, page).results.first { it.systemType == SystemShelfType.WATCHLIST }, 11)
        shelves.addMovie(shelves.createShelf("ana", ShelfDraft("Noir", null, null, Visibility.PUBLIC)), 10)
        shelves.addMovie(shelves.createShelf("bia", ShelfDraft("Segredo", null, null, Visibility.SECRET)), 10)

        val community = watch.community(10)

        assertEquals(mapOf(Rating.FAVORITE to 1, Rating.LIKED to 1), community.ratings)
        assertEquals(2, community.watched)
        assertEquals(1, community.shelves)
        assertEquals(listOf(EmotionCount(Emotion.JOY, 2), EmotionCount(Emotion.FEAR, 1)), community.emotions)
        assertEquals(1, watch.community(11).wantToWatch)
        assertEquals(emptyMap(), watch.community(11).ratings)
    }

    @Test
    fun `the community emotions are the full distribution, most frequent first then by id`() {
        val picked = Emotion.entries.take(12)
        watch.createLog("ana", 10, draft(emotions = picked.take(6)))
        watch.createLog("ana", 10, draft(emotions = picked.drop(6)))
        watch.createLog("ana", 10, draft(emotions = listOf(picked.last())))

        val emotions = watch.community(10).emotions

        assertEquals(12, emotions.size)
        assertEquals(EmotionCount(picked.last(), 2), emotions.first())
        assertEquals(picked.dropLast(1).sortedBy { it.id }, emotions.drop(1).map { it.emotion })
        assertTrue(watch.community(11).emotions.isEmpty())
    }

    @Test
    fun `the activity counts public sessions by day and public ratings by day inside the window`() {
        listOf("bia", "caio").forEach {
            users.save(User(id = it, email = "$it@x.com", username = it, avatar = null, bio = null, createdAt = ""))
        }
        watch.createLog("ana", 10, draft(watchedOn = LocalDate(2026, 10, 1)))
        watch.createLog("ana", 10, draft(watchedOn = LocalDate(2026, 10, 15)))
        watch.createLog("ana", 10, draft(watchedOn = LocalDate(2026, 8, 31)))
        watch.createLog("bia", 10, draft(watchedOn = LocalDate(2026, 9, 3)))
        watch.createLog("caio", 10, draft(watchedOn = LocalDate(2026, 10, 2), visibility = Visibility.SECRET))
        watch.setRating("ana", 10, Rating.FAVORITE, null, false)
        watch.setRating("bia", 10, Rating.LIKED, null, false)
        watch.setRating("caio", 10, Rating.NOT_FOR_ME, null, false)
        execute(
            "UPDATE shelves SET visibility = 'secret' WHERE system_type = 'watched' " +
                "AND user_id = (SELECT id FROM users WHERE external_id = 'caio');"
        )
        execute("UPDATE user_movies SET rated_at = '2026-10-05 10:00:00' WHERE user_id = (SELECT id FROM users WHERE external_id = 'ana');")
        execute("UPDATE user_movies SET rated_at = '2026-09-30 12:00:00' WHERE user_id = (SELECT id FROM users WHERE external_id = 'bia');")
        execute("UPDATE user_movies SET rated_at = '2026-10-06 10:00:00' WHERE user_id = (SELECT id FROM users WHERE external_id = 'caio');")

        val activity = watch.activity(10, LocalDate(2026, 9, 1), LocalDate(2026, 11, 1))

        assertEquals(
            mapOf(LocalDate(2026, 9, 3) to 1, LocalDate(2026, 10, 1) to 1, LocalDate(2026, 10, 15) to 1),
            activity.sessionsByDay
        )
        assertEquals(mapOf(LocalDate(2026, 9, 30) to 1, LocalDate(2026, 10, 5) to 1), activity.ratingsByDay)
        assertTrue(watch.activity(11, LocalDate(2026, 9, 1), LocalDate(2026, 11, 1)).sessionsByDay.isEmpty())
    }

    private fun draft(
        mode: WatchMode = WatchMode.WATCHED,
        minutes: Int? = 100,
        percent: Int? = 100,
        emotions: List<Emotion> = emptyList(),
        gif: Gif? = null,
        visibility: Visibility = Visibility.PUBLIC,
        watchedOn: LocalDate = LocalDate(2026, 10, 1)
    ) = WatchLogDraft(
        watchedOn = watchedOn,
        mode = mode,
        minutesWatched = minutes,
        percent = percent,
        completed = mode == WatchMode.WATCHED,
        venue = null,
        providerName = null,
        emotions = emotions,
        gif = gif,
        comment = null,
        hasSpoiler = false,
        visibility = visibility
    )

    private fun system(type: SystemShelfType) =
        shelves.getUserShelves("ana", everyone, page).results.first { it.systemType == type }

    private fun movieIds(type: SystemShelfType): List<Long> =
        shelves.getShelfMovies(system(type), org.mobyle.domain.model.ShelfMovieFilter(), page).results.map { it.movieId }

    private fun execute(sql: String) = dataSource.connection.use { it.createStatement().execute(sql) }

    private fun query(sql: String): Any? = dataSource.connection.use { connection ->
        connection.createStatement().use { stmt -> stmt.executeQuery(sql).use { rs -> rs.next(); rs.getObject(1) } }
    }

    companion object {
        private val postgres: EmbeddedPostgres by lazy { EmbeddedPostgres.start() }
        private val counter = AtomicInteger()
    }
}
