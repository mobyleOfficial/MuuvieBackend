package org.mobyle.data.local.shelves

import io.mockk.mockk
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import kotlinx.datetime.Clock
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.mobyle.data.local.database.DatabaseMigrations
import org.mobyle.data.local.database.ShelfVisitsTable
import org.mobyle.data.local.database.UserMoviesTable
import org.mobyle.data.local.user.UserDatabaseDataSourceImpl
import org.mobyle.data.local.watch.WatchDataSourceImpl
import org.mobyle.domain.model.PageRequest
import org.mobyle.domain.model.Shelf
import org.mobyle.domain.model.ShelfDraft
import org.mobyle.domain.model.ShelfMovieFilter
import org.mobyle.domain.model.ShelfTransferMode
import org.mobyle.domain.model.User
import org.mobyle.domain.model.vocabulary.Rating
import org.mobyle.domain.model.vocabulary.ShelfSort
import org.mobyle.domain.model.vocabulary.SystemShelfType
import org.mobyle.domain.model.vocabulary.Visibility
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days

class ShelfDataSourceImplTest {

    private lateinit var dataSource: DataSource
    private val shelves = ShelfDataSourceImpl()
    private val users = UserDatabaseDataSourceImpl()
    private val watch = WatchDataSourceImpl()
    private val page = PageRequest(1, 50)

    @BeforeTest
    fun setUp() {
        // One embedded server per class, one fresh database per test.
        val name = "shelves_${counter.incrementAndGet()}"
        postgres.postgresDatabase.connection.use { it.createStatement().execute("CREATE DATABASE $name") }
        dataSource = postgres.getDatabase("postgres", name)
        DatabaseMigrations.migrate(dataSource)
        Database.connect(dataSource)

        users.save(User(id = "ana", email = "ana@x.com", username = "ana", avatar = null, bio = null, createdAt = ""))
        users.save(User(id = "bia", email = "bia@x.com", username = "bia", avatar = null, bio = null, createdAt = ""))
        execute(
            """
            INSERT INTO movies (id, tmdb_id, title, "year", director) VALUES
                (10, 100, 'Casablanca', 1942, 'Curtiz'), (11, 101, 'Alien', 1979, 'Scott'),
                (12, 102, 'Blade Runner', 1982, 'Scott'), (13, 103, 'Zodiac', 2007, 'Fincher');
            INSERT INTO genres (id, tmdb_id, name) VALUES (1, 878, 'Sci-Fi');
            INSERT INTO movie_genres (movie_id, genre_id) VALUES (11, 1), (12, 1);
            """
        )
    }

    @Test
    fun `signup creates both system shelves, public and first on the profile`() {
        val result = shelves.getUserShelves("ana", Visibility.entries.toSet(), page).results

        assertEquals(listOf(SystemShelfType.WATCHED, SystemShelfType.WATCHLIST), result.map { it.systemType })
        assertEquals(listOf("Já assisti", "Quero ver"), result.map { it.name })
        assertTrue(result.all { it.visibility == Visibility.PUBLIC })
    }

    @Test
    fun `adding to Ja assisti marks as watched and takes the movie off Quero ver`() {
        shelves.addMovie(system("ana", SystemShelfType.WATCHLIST), 10)
        assertEquals("want_to_watch", status("ana", 10))

        shelves.addMovie(system("ana", SystemShelfType.WATCHED), 10)

        assertEquals("watched", status("ana", 10))
        assertEquals(listOf(10L), movieIds(system("ana", SystemShelfType.WATCHED)))
        assertEquals(emptyList(), movieIds(system("ana", SystemShelfType.WATCHLIST)))
    }

    @Test
    fun `public custom shelves with a movie list their owner, never system or private shelves`() {
        val noir = create("ana", "Noir")
        shelves.addMovie(noir, 10)
        shelves.addMovie(create("bia", "Segredo", Visibility.SECRET), 10)
        shelves.addMovie(system("bia", SystemShelfType.WATCHED), 10)
        shelves.addMovie(create("bia", "Outra"), 11)

        val result = shelves.publicShelvesWithMovie(10, page)

        assertEquals(1, result.totalResults)
        assertEquals(noir.id, result.results.single().shelf.id)
        assertEquals("ana", result.results.single().owner.id)
    }

    @Test
    fun `sort by rating follows the order of the labels, unrated last`() {
        val shelf = create("ana", "Mix")
        listOf(10L, 11L, 12L, 13L).forEach { shelves.addMovie(shelf, it) }
        watch.setRating("ana", 10, Rating.NOT_FOR_ME, null, false)
        watch.setRating("ana", 11, Rating.FAVORITE, null, false)
        watch.setRating("ana", 13, Rating.LIKED, null, false)

        shelves.sortShelf(shelf.id, ShelfSort.RATING_HIGHEST, applyOnce = false)

        assertEquals(listOf(11L, 13L, 10L, 12L), movieIds(shelves.getShelf(shelf.id)!!))
    }

    @Test
    fun `removing from Ja assisti unmarks the movie but keeps its rating`() {
        shelves.addMovie(system("ana", SystemShelfType.WATCHED), 10)
        watch.setRating("ana", 10, Rating.LOVED, null, false)
        assertEquals(listOf(10L), movieIds(system("ana", SystemShelfType.WATCHED)))

        shelves.removeMovie(system("ana", SystemShelfType.WATCHED), 10)

        assertEquals("none", status("ana", 10))
        assertEquals("loved", rating("ana", 10))
        assertEquals(emptyList(), movieIds(system("ana", SystemShelfType.WATCHED)))
    }

    @Test
    fun `move and copy between shelves keep the movie state`() {
        val from = create("ana", "From")
        val to = create("ana", "To")
        listOf(10L, 11L, 12L).forEach { shelves.addMovie(from, it) }
        watch.setRating("ana", 11, Rating.NEUTRAL, null, false)

        shelves.transferMovies(from, to, ShelfTransferMode.COPY, listOf(10))
        shelves.transferMovies(from, to, ShelfTransferMode.MOVE, listOf(11))

        assertEquals(listOf(10L, 12L), movieIds(from))
        assertEquals(listOf(10L, 11L), movieIds(to))
        assertEquals("neutral", rating("ana", 11))
    }

    @Test
    fun `moving out of Quero ver into Ja assisti marks the movies as watched`() {
        shelves.addMovie(system("ana", SystemShelfType.WATCHLIST), 10)
        shelves.addMovie(system("ana", SystemShelfType.WATCHLIST), 11)

        shelves.transferMovies(
            system("ana", SystemShelfType.WATCHLIST), system("ana", SystemShelfType.WATCHED), ShelfTransferMode.MOVE, emptyList()
        )

        assertEquals(listOf(10L, 11L), movieIds(system("ana", SystemShelfType.WATCHED)).sorted())
        assertEquals(emptyList(), movieIds(system("ana", SystemShelfType.WATCHLIST)))
        assertEquals("watched", status("ana", 11))
    }

    @Test
    fun `sort applied once becomes the manual order`() {
        val shelf = create("ana", "Sorted")
        listOf(13L, 10L, 12L, 11L).forEach { shelves.addMovie(shelf, it) }

        shelves.sortShelf(shelf.id, ShelfSort.TITLE, applyOnce = true)
        assertEquals(listOf(11L, 12L, 10L, 13L), movieIds(shelves.getShelf(shelf.id)!!))
        assertEquals(ShelfSort.MANUAL, shelves.getShelf(shelf.id)!!.sortMode)

        shelves.reorderMovies(shelf.id, listOf(13L))
        assertEquals(listOf(13L, 11L, 12L, 10L), movieIds(shelves.getShelf(shelf.id)!!))

        shelves.sortShelf(shelf.id, ShelfSort.RELEASE_YEAR, applyOnce = false)
        assertEquals(listOf(10L, 11L, 12L, 13L), movieIds(shelves.getShelf(shelf.id)!!))
    }

    @Test
    fun `filters combine watched, genre and decade`() {
        val shelf = create("ana", "Mixed")
        listOf(10L, 11L, 12L, 13L).forEach { shelves.addMovie(shelf, it) }
        shelves.addMovie(system("ana", SystemShelfType.WATCHED), 12)

        assertEquals(listOf(11L, 12L), movieIds(shelf, ShelfMovieFilter(genreTmdbId = 878)))
        assertEquals(listOf(12L), movieIds(shelf, ShelfMovieFilter(genreTmdbId = 878, watched = true)))
        assertEquals(listOf(11L), movieIds(shelf, ShelfMovieFilter(watched = false, decade = 1970)))
        val counted = shelves.getShelf(shelf.id)!!
        assertEquals(4, counted.totalMovies)
        assertEquals(1, counted.totalWatched)
    }

    @Test
    fun `profile order puts pinned shelves first and hides what the viewer cannot see`() {
        val secret = create("ana", "Secret", Visibility.SECRET)
        val mutual = create("ana", "Mutual", Visibility.MUTUAL)
        val public = create("ana", "Public")

        shelves.reorderShelves("ana", listOf(public.id), pinnedIds = listOf(mutual.id))

        val all = shelves.getUserShelves("ana", Visibility.entries.toSet(), page).results.map { it.name }
        assertEquals(listOf("Mutual", "Public", "Já assisti", "Quero ver", "Secret"), all)
        val publicOnly = shelves.getUserShelves("ana", setOf(Visibility.PUBLIC), page)
        assertEquals(listOf("Public", "Já assisti", "Quero ver"), publicOnly.results.map { it.name })
        assertEquals(3, publicOnly.totalResults)
        assertNull(shelves.getUserShelves("ana", setOf(Visibility.PUBLIC), page).results.find { it.id == secret.id })
    }

    @Test
    fun `mutuals need both follows and deleting a shelf drops its links`() {
        execute("INSERT INTO user_follows (follower_id, followed_id, created_at) VALUES (1, 2, now())")
        assertEquals(false, shelves.areMutuals("ana", "bia"))
        execute("INSERT INTO user_follows (follower_id, followed_id, created_at) VALUES (2, 1, now())")
        assertEquals(true, shelves.areMutuals("ana", "bia"))

        val shelf = create("ana", "Gone")
        shelves.addMovie(shelf, 10)
        execute(
            """INSERT INTO locadoras (id, user_id, name, created_at) VALUES (1, 1, 'Loc', now());
               INSERT INTO locadora_shelves (locadora_id, shelf_id, position, added_at) VALUES (1, ${shelf.id}, 0, now());"""
        )

        shelves.deleteShelf(shelf.id)

        assertNull(shelves.getShelf(shelf.id))
    }

    @Test
    fun `a movie known only by its TMDB id becomes a stub to enrich`() {
        val id = shelves.resolveMovieId(movieId = null, tmdbId = 999, title = "New")!!

        assertEquals(id, shelves.resolveMovieId(movieId = null, tmdbId = 999, title = "Other"))
        assertEquals(id, shelves.findMovieIdByTmdbId(999))
        assertNull(shelves.resolveMovieId(movieId = 12345, tmdbId = null, title = null))
    }

    @Test
    fun `saving twice keeps one save, and the shelf carries its owner and save count`() {
        val giallo = create("ana", "Giallo")

        shelves.saveShelf(giallo.id, "bia")
        shelves.saveShelf(giallo.id, "bia")

        val saved = shelves.getShelf(giallo.id)!!
        assertEquals(1, saved.saves)
        assertEquals("ana", saved.owner?.id)
        assertEquals("ana", saved.owner?.username)
        assertTrue(shelves.isSaved(giallo.id, "bia"))

        shelves.unsaveShelf(giallo.id, "bia")
        shelves.unsaveShelf(giallo.id, "bia")

        assertEquals(0, shelves.getShelf(giallo.id)!!.saves)
        assertFalse(shelves.isSaved(giallo.id, "bia"))
    }

    @Test
    fun `saved shelves come last saved first, only while the viewer can still see them`() {
        val older = create("ana", "Antiga")
        val newer = create("ana", "Nova")
        val mutual = create("ana", "Mutua", Visibility.MUTUAL)
        listOf(older, newer, mutual).forEach { shelves.saveShelf(it.id, "bia") }
        execute("UPDATE shelf_saves SET created_at = now() - interval '2 days' WHERE shelf_id = ${older.id}")
        execute("UPDATE shelf_saves SET created_at = now() - interval '1 day' WHERE shelf_id = ${newer.id}")

        // Not mutuals yet: the mutual shelf stays out.
        assertEquals(listOf(newer.id, older.id), shelves.savedShelves("bia", page).results.map { it.id })

        follow("bia", "ana")
        follow("ana", "bia")
        execute("UPDATE shelves SET visibility = 'secret' WHERE id = ${older.id}")

        val saved = shelves.savedShelves("bia", page)
        assertEquals(listOf(mutual.id, newer.id), saved.results.map { it.id })
        assertTrue(saved.results.all { it.saved })
        assertEquals(2, saved.totalResults)
    }

    @Test
    fun `a visit keeps one row per person, with the last time`() {
        val giallo = create("ana", "Giallo")

        shelves.recordVisit(giallo.id, "bia")
        execute("UPDATE shelf_visits SET visited_at = now() - interval '40 days'")
        shelves.recordVisit(giallo.id, "bia")

        val visits = transaction { ShelfVisitsTable.selectAll().map { it[ShelfVisitsTable.visitedAt] } }
        assertEquals(1, visits.size)
        assertTrue(visits.single() > Clock.System.now() - 1.days)
    }

    private fun follow(follower: String, followed: String) = execute(
        "INSERT INTO user_follows (follower_id, followed_id, created_at) " +
            "SELECT a.id, b.id, now() FROM users a, users b WHERE a.external_id = '$follower' AND b.external_id = '$followed'"
    )

    private fun system(userId: String, type: SystemShelfType): Shelf =
        shelves.getUserShelves(userId, Visibility.entries.toSet(), page).results.single { it.systemType == type }

    private fun create(userId: String, name: String, visibility: Visibility = Visibility.PUBLIC): Shelf =
        shelves.createShelf(userId, ShelfDraft(name, description = null, color = null, visibility = visibility))

    private fun movieIds(shelf: Shelf, filter: ShelfMovieFilter = ShelfMovieFilter()): List<Long> =
        shelves.getShelfMovies(shelf, filter, page).results.map { it.movieId }

    private fun userMovie(userId: String, movieId: Long) = transaction {
        val userDbId = if (userId == "ana") 1L else 2L
        UserMoviesTable.selectAll()
            .where { (UserMoviesTable.userId eq userDbId) and (UserMoviesTable.movieId eq movieId) }
            .single()
    }

    private fun status(userId: String, movieId: Long) = userMovie(userId, movieId)[UserMoviesTable.status]

    private fun rating(userId: String, movieId: Long) = userMovie(userId, movieId)[UserMoviesTable.rating]

    private fun execute(sql: String) = dataSource.connection.use { it.createStatement().execute(sql) }

    companion object {
        private val postgres: EmbeddedPostgres by lazy { EmbeddedPostgres.start() }
        private val counter = AtomicInteger()
    }
}
