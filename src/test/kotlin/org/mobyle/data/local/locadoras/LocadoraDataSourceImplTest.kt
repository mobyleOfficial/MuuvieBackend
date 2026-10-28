package org.mobyle.data.local.locadoras

import io.mockk.mockk
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.jetbrains.exposed.sql.Database
import org.mobyle.data.local.database.DatabaseMigrations
import org.mobyle.data.local.shelves.ShelfDataSourceImpl
import org.mobyle.data.local.user.UserDatabaseDataSourceImpl
import org.mobyle.domain.model.Locadora
import org.mobyle.domain.model.LocadoraDraft
import org.mobyle.domain.model.PageRequest
import org.mobyle.domain.model.Shelf
import org.mobyle.domain.model.ShelfDraft
import org.mobyle.domain.model.ShelfMovieFilter
import org.mobyle.domain.model.User
import org.mobyle.domain.model.vocabulary.SystemShelfType
import org.mobyle.domain.model.vocabulary.Visibility
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocadoraDataSourceImplTest {

    private lateinit var dataSource: DataSource
    private val shelves = ShelfDataSourceImpl()
    private val locadoras = LocadoraDataSourceImpl(shelves)
    private val users = UserDatabaseDataSourceImpl()
    private val page = PageRequest(1, 50)
    private val everyone = Visibility.entries.toSet()
    private val publicOnly = setOf(Visibility.PUBLIC)

    @BeforeTest
    fun setUp() {
        // One embedded server per class, one fresh database per test.
        val name = "locadoras_${counter.incrementAndGet()}"
        postgres.postgresDatabase.connection.use { it.createStatement().execute("CREATE DATABASE $name") }
        dataSource = postgres.getDatabase("postgres", name)
        DatabaseMigrations.migrate(dataSource)
        Database.connect(dataSource)

        users.save(User(id = "ana", email = "ana@x.com", username = "ana", avatar = null, bio = null, createdAt = ""))
        execute(
            """
            INSERT INTO movies (id, tmdb_id, title, "year", poster_path) VALUES
                (10, 100, 'Halloween', 1978, '/h.jpg'), (11, 101, 'Suspiria', 1977, '/s.jpg'),
                (12, 102, 'The Wicker Man', 1973, '/w.jpg'), (13, 103, 'Ringu', 1998, '/r.jpg');
            """
        )
    }

    @Test
    fun `deleting a locadora keeps its shelves`() {
        val slashers = shelf("Slashers 80s", 10)
        val locadora = create("Locadora do Terror")
        locadoras.setShelves(locadora.id, listOf(slashers.id))

        locadoras.deleteLocadora(locadora.id)

        assertNull(locadoras.getLocadora(locadora.id, everyone))
        assertNotNull(shelves.getShelf(slashers.id))
        assertEquals(listOf(10L), shelves.getShelfMovies(slashers, ShelfMovieFilter(), page).results.map { it.movieId })
    }

    @Test
    fun `a secret shelf in a public locadora stays out of what others see`() {
        val folk = shelf("Folk horror", 12)
        val hidden = shelf("Terror japonês", 13, visibility = Visibility.SECRET)
        val locadora = create("Locadora do Terror")
        locadoras.setShelves(locadora.id, listOf(folk.id, hidden.id))

        val forOthers = locadoras.getLocadoraShelves(locadora.id, publicOnly, page, 10)
        assertEquals(listOf("Folk horror"), forOthers.results.map { it.shelf.name })
        assertEquals(1, forOthers.totalResults)
        val summary = locadoras.getLocadora(locadora.id, publicOnly)!!
        assertEquals(1, summary.totalShelves)
        assertEquals(1, summary.totalMovies)
        assertEquals(listOf("/w.jpg"), summary.posterPaths)

        val forOwner = locadoras.getLocadoraShelves(locadora.id, everyone, page, 10)
        assertEquals(listOf("Folk horror", "Terror japonês"), forOwner.results.map { it.shelf.name })
    }

    @Test
    fun `replacing the shelves keeps the order of the ones that stay and the order persists`() {
        val a = shelf("A", 10)
        val b = shelf("B", 11)
        val c = shelf("C", 12)
        val locadora = create("Loc")
        locadoras.setShelves(locadora.id, listOf(a.id, b.id, c.id))

        locadoras.reorderShelves(locadora.id, listOf(c.id, a.id))
        assertEquals(listOf(c.id, a.id, b.id), locadoras.locadoraShelfIds(locadora.id))

        val d = shelf("D", 13)
        locadoras.setShelves(locadora.id, listOf(d.id, b.id, c.id))
        assertEquals(listOf(c.id, b.id, d.id), locadoras.locadoraShelfIds(locadora.id))
        assertEquals(
            listOf("C", "B", "D"),
            locadoras.getLocadoraShelves(locadora.id, everyone, page, 10).results.map { it.shelf.name }
        )
    }

    @Test
    fun `progress counts each movie once and the watched ones of the owner`() {
        val a = shelf("A", 10, 11)
        val b = shelf("B", 11, 12)
        shelves.addMovie(system(SystemShelfType.WATCHED), 11)
        val locadora = create("Loc")
        locadoras.setShelves(locadora.id, listOf(a.id, b.id))

        val result = locadoras.getLocadora(locadora.id, everyone)!!

        assertEquals(2, result.totalShelves)
        assertEquals(3, result.totalMovies)
        assertEquals(1, result.totalWatched)
        assertEquals(listOf("/h.jpg", "/s.jpg", "/w.jpg"), result.posterPaths)
        val corridor = locadoras.getLocadoraShelves(locadora.id, everyone, page, 1).results.first()
        assertEquals(listOf(10L), corridor.movies.map { it.movieId })
    }

    @Test
    fun `user locadoras follow visibility and profile order`() {
        val secret = create("Secreta", Visibility.SECRET)
        val first = create("Primeira")
        val second = create("Segunda")

        locadoras.reorderLocadoras("ana", listOf(second.id), pinnedIds = listOf(first.id))

        assertEquals(
            listOf("Primeira", "Segunda", "Secreta"),
            locadoras.getUserLocadoras("ana", everyone, page).results.map { it.name }
        )
        val forOthers = locadoras.getUserLocadoras("ana", publicOnly, page)
        assertEquals(listOf("Primeira", "Segunda"), forOthers.results.map { it.name })
        assertTrue(forOthers.results.none { it.id == secret.id })
    }

    @Test
    fun `a locadora can hide its shelves from the profile shelf tab`() {
        val inside = shelf("Dentro", 10)
        val outside = shelf("Fora", 11)
        val locadora = create("Loc")
        locadoras.setShelves(locadora.id, listOf(inside.id))
        locadoras.updateLocadora(locadora.id, draft("Loc", hideShelvesInProfile = true))

        val names = shelves.getUserShelves("ana", everyone, page, excludeHiddenByLocadoras = true).results.map { it.name }
        assertTrue(outside.name in names)
        assertTrue(inside.name !in names)
        assertTrue(inside.name in shelves.getUserShelves("ana", everyone, page).results.map { it.name })
    }

    private fun draft(
        name: String,
        visibility: Visibility = Visibility.PUBLIC,
        hideShelvesInProfile: Boolean = false
    ) = LocadoraDraft(name, description = null, visibility = visibility, hideShelvesInProfile = hideShelvesInProfile, hideShelvesInFilters = false)

    private fun create(name: String, visibility: Visibility = Visibility.PUBLIC): Locadora =
        locadoras.createLocadora("ana", draft(name, visibility))

    private fun shelf(name: String, vararg movieIds: Long, visibility: Visibility = Visibility.PUBLIC): Shelf {
        val shelf = shelves.createShelf("ana", ShelfDraft(name, description = null, color = null, visibility = visibility))
        movieIds.forEach { shelves.addMovie(shelf, it) }
        return shelf
    }

    private fun system(type: SystemShelfType): Shelf =
        shelves.getUserShelves("ana", everyone, page).results.single { it.systemType == type }

    private fun execute(sql: String) = dataSource.connection.use { it.createStatement().execute(sql) }

    companion object {
        private val postgres: EmbeddedPostgres by lazy { EmbeddedPostgres.start() }
        private val counter = AtomicInteger()
    }
}
