package org.mobyle.data.local.discover

import io.mockk.mockk
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import kotlinx.datetime.Clock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.todayIn
import org.jetbrains.exposed.sql.Database
import org.mobyle.data.local.database.DatabaseMigrations
import org.mobyle.data.local.locadoras.LocadoraDataSourceImpl
import org.mobyle.data.local.shelves.ShelfDataSourceImpl
import org.mobyle.data.local.user.UserDatabaseDataSourceImpl
import org.mobyle.domain.model.LocadoraDraft
import org.mobyle.domain.model.PageRequest
import org.mobyle.domain.model.Shelf
import org.mobyle.domain.model.ShelfDraft
import org.mobyle.domain.model.User
import org.mobyle.domain.model.vocabulary.Visibility
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.days

class DiscoverDataSourceImplTest {

    private lateinit var dataSource: DataSource
    private val discover = DiscoverDataSourceImpl()
    private val shelves = ShelfDataSourceImpl()
    private val locadoras = LocadoraDataSourceImpl(shelves)
    private val users = UserDatabaseDataSourceImpl()
    private val page = PageRequest(1, 50)
    private val since get() = Clock.System.now() - 30.days

    @BeforeTest
    fun setUp() {
        val name = "discover_${counter.incrementAndGet()}"
        postgres.postgresDatabase.connection.use { it.createStatement().execute("CREATE DATABASE $name") }
        dataSource = postgres.getDatabase("postgres", name)
        DatabaseMigrations.migrate(dataSource)
        Database.connect(dataSource)

        listOf("ana", "bia", "caio").forEach {
            users.save(User(id = it, email = "$it@x.com", username = it, avatar = null, bio = null, createdAt = ""))
        }
        execute("INSERT INTO movies (id, tmdb_id, title) VALUES (10, 100, 'A'), (11, 101, 'B'), (12, 102, 'C');")
    }

    @Test
    fun `only public, non-empty user shelves of others show up`() {
        val public = shelf("bia", "Giallo", 10)
        shelf("bia", "Empty")
        shelf("bia", "Secret", 10, visibility = Visibility.SECRET)
        shelf("bia", "Mutual", 10, visibility = Visibility.MUTUAL)
        shelf("ana", "Mine", 10)
        // System shelves are never discovered, even when public and filled.
        execute("INSERT INTO shelf_items (shelf_id, movie_id, position, added_at) SELECT id, 11, 0, now() FROM shelves WHERE system_type = 'watched'")

        val ids = discover.shelfIds(excludeUserId = "ana", query = null, since = since, page = page)

        assertEquals(listOf(public.id), ids.results)
    }

    @Test
    fun `a search also finds the viewer's own shelves, locadoras and profile, whatever their visibility`() {
        val mine = shelf("ana", "Giallo meu", 10)
        val secret = shelf("ana", "Giallo secreto", visibility = Visibility.SECRET)
        val theirs = shelf("bia", "Giallo dela", 10)
        shelf("bia", "Giallo escondido", 10, visibility = Visibility.SECRET)
        val locadora = locadoras.createLocadora("ana", LocadoraDraft("Locadora da Ana", null, Visibility.SECRET, false, false))

        assertEquals(
            setOf(mine.id, secret.id, theirs.id),
            discover.shelfIds("ana", "giallo", since, page).results.toSet()
        )
        assertEquals(listOf(locadora.id), discover.locadoraIds("ana", "ana", since, page).results)
        assertEquals(listOf("ana"), discover.userIds("ana", "ana", since, page).results)
        // Without a query the hub keeps them out.
        assertEquals(listOf(theirs.id), discover.shelfIds("ana", null, since, page).results)
    }

    @Test
    fun `shelves a locadora hides from the filters stay out`() {
        val hidden = shelf("bia", "Hidden", 10)
        val visible = shelf("bia", "Visible", 10)
        val locadora = locadoras.createLocadora("bia", LocadoraDraft("Loc", null, Visibility.PUBLIC, false, hideShelvesInFilters = true))
        locadoras.setShelves(locadora.id, listOf(hidden.id))

        assertEquals(listOf(visible.id), discover.shelfIds(null, null, since, page).results)
    }

    @Test
    fun `between equally popular shelves recent activity wins, curated or not, and the name filter applies`() {
        val quiet = shelf("bia", "Terror antigo", 10)
        execute("UPDATE shelf_items SET added_at = now() - interval '90 days' WHERE shelf_id = ${quiet.id}")
        val active = shelf("bia", "Terror novo", 10, 11, 12)
        val curated = shelf("caio", "Terror curado", 10)
        execute("UPDATE users SET featured = true WHERE external_id = 'caio'")

        assertEquals(listOf(active.id, curated.id, quiet.id), discover.shelfIds(null, "terror", since, page).results)
        assertEquals(listOf(active.id), discover.shelfIds(null, "NOVO", since, page).results)
    }

    @Test
    fun `popular shelves add saves, loves, recent visitors and the owner's followers, never the owner`() {
        listOf("duda", "edu").forEach {
            users.save(User(id = it, email = "$it@x.com", username = it, avatar = null, bio = null, createdAt = ""))
        }
        val none = shelf("ana", "Nada", 10)
        val followed = shelf("edu", "Seguida", 10)
        val visited = shelf("duda", "Visitada", 10)
        val saved = shelf("bia", "Salva", 10)
        val loved = shelf("caio", "Amada", 10)

        // 1: ana follows edu.
        follow("ana", "edu")
        // 3: ana's save; bia's own save does not count.
        save(saved.id, "ana")
        save(saved.id, "bia")
        // 4: ana and bia love a public activity about it; caio is the owner and duda's love is on a hidden one.
        val activity = activity("caio", loved.id)
        love(activity, "ana")
        love(activity, "bia")
        love(activity, "caio")
        love(activity("caio", loved.id, hidden = true), "duda")
        // 2: ana and bia visited it lately; caio's visit is too old and duda is the owner.
        visit(visited.id, "ana")
        visit(visited.id, "bia")
        visit(visited.id, "duda")
        visit(visited.id, "caio", daysAgo = 40)

        assertEquals(
            listOf(loved.id, saved.id, visited.id, followed.id, none.id),
            discover.shelfIds(null, null, since, page).results
        )
    }

    @Test
    fun `locadoras need to be public and hold at least one public shelf`() {
        val withPublic = locadoras.createLocadora("bia", LocadoraDraft("Com", null, Visibility.PUBLIC, false, false))
        locadoras.setShelves(withPublic.id, listOf(shelf("bia", "P", 10).id))
        val onlySecret = locadoras.createLocadora("bia", LocadoraDraft("Sem", null, Visibility.PUBLIC, false, false))
        locadoras.setShelves(onlySecret.id, listOf(shelf("bia", "S", 10, visibility = Visibility.SECRET).id))
        val secret = locadoras.createLocadora("bia", LocadoraDraft("Secreta", null, Visibility.SECRET, false, false))
        locadoras.setShelves(secret.id, listOf(shelf("bia", "P2", 10).id))

        assertEquals(listOf(withPublic.id), discover.locadoraIds(null, null, since, page).results)
    }

    @Test
    fun `profiles leave the viewer out and rank curated, then active users`() {
        shelf("caio", "Ativa", 10, 11)
        execute("UPDATE users SET featured = true WHERE external_id = 'bia'")

        assertEquals(listOf("bia", "caio"), discover.userIds(excludeUserId = "ana", query = null, since = since, page = page).results)
    }

    @Test
    fun `profiles filter by username, handle or display name`() {
        execute("UPDATE users SET handle = 'cinefila_bia' WHERE external_id = 'bia'")
        execute("UPDATE users SET display_name = 'Caio Lima' WHERE external_id = 'caio'")

        assertEquals(listOf("bia"), discover.userIds(null, "CINEFILA", since, page).results)
        assertEquals(listOf("caio"), discover.userIds(null, "lima", since, page).results)
        assertEquals(listOf("ana"), discover.userIds(null, "an", since, page).results)
    }

    @Test
    fun `trending counts distinct people with public sessions inside the window`() {
        val today = Clock.System.todayIn(TimeZone.UTC)
        log("ana", 10, today)
        log("ana", 10, today)
        log("bia", 11, today)
        log("caio", 11, today)
        log("caio", 12, today, visibility = Visibility.SECRET)
        log("bia", 12, today.minus(DatePeriod(days = 10)))

        val ids = discover.trendingMovieIds(today.minus(DatePeriod(days = 6)), page)

        assertEquals(listOf(11L, 10L), ids.results)
        assertEquals(2, ids.totalResults)
    }

    @Test
    fun `loved counts Favoritado and Adorei of public Já assisti only, above the minimum`() {
        execute("UPDATE shelves SET visibility = 'public' WHERE system_type = 'watched'")
        execute("UPDATE shelves SET visibility = 'secret' WHERE system_type = 'watched' AND user_id = (SELECT id FROM users WHERE external_id = 'caio')")
        rate("ana", 10, "favorite")
        rate("bia", 10, "loved")
        rate("ana", 11, "loved")
        rate("bia", 11, "loved")
        rate("caio", 11, "loved")
        rate("ana", 12, "liked")
        rate("bia", 12, "favorite")
        rate("caio", 12, "favorite")

        // 11 has caio's private label left out, so it ties with 10 and the newest id wins.
        assertEquals(listOf(11L, 10L), discover.lovedMovieIds(minRatings = 2, page = page).results)
        assertEquals(listOf(11L, 10L, 12L), discover.lovedMovieIds(minRatings = 1, page = page).results)
    }

    private fun log(user: String, movieId: Long, on: LocalDate, visibility: Visibility = Visibility.PUBLIC) = execute(
        "INSERT INTO watch_logs (user_id, movie_id, watched_on, mode, visibility, created_at, updated_at) " +
            "SELECT id, $movieId, '$on', 'watched', '${visibility.wireValue}', now(), now() FROM users WHERE external_id = '$user'"
    )

    private fun rate(user: String, movieId: Long, rating: String) = execute(
        "INSERT INTO user_movies (user_id, movie_id, status, rating, source, created_at, updated_at) " +
            "SELECT id, $movieId, 'watched', '$rating', 'manual', now(), now() FROM users WHERE external_id = '$user'"
    )

    private fun shelf(owner: String, name: String, vararg movieIds: Long, visibility: Visibility = Visibility.PUBLIC): Shelf {
        val shelf = shelves.createShelf(owner, ShelfDraft(name, description = null, color = null, visibility = visibility))
        movieIds.forEach { shelves.addMovie(shelf, it) }
        return shelf
    }

    private fun userDbId(user: String) = "(SELECT id FROM users WHERE external_id = '$user')"

    private fun follow(follower: String, followed: String) = execute(
        "INSERT INTO user_follows (follower_id, followed_id, created_at) VALUES (${userDbId(follower)}, ${userDbId(followed)}, now())"
    )

    private fun save(shelfId: Long, user: String) =
        execute("INSERT INTO shelf_saves (shelf_id, user_id) VALUES ($shelfId, ${userDbId(user)})")

    private fun visit(shelfId: Long, user: String, daysAgo: Int = 0) = execute(
        "INSERT INTO shelf_visits (shelf_id, user_id, visited_at) VALUES ($shelfId, ${userDbId(user)}, now() - interval '$daysAgo days')"
    )

    private fun activity(actor: String, shelfId: Long, hidden: Boolean = false): Long =
        dataSource.connection.use { connection ->
            connection.createStatement().executeQuery(
                "INSERT INTO activities (actor_id, type, visibility, subject_type, subject_id, payload, hidden) " +
                    "VALUES (${userDbId(actor)}, 'nova_estante', 'public', 'shelf', $shelfId, '{}', $hidden) RETURNING id"
            ).use { it.next(); it.getLong(1) }
        }

    private fun love(activityId: Long, user: String) =
        execute("INSERT INTO activity_loves (activity_id, user_id) VALUES ($activityId, ${userDbId(user)})")

    private fun execute(sql: String) = dataSource.connection.use { it.createStatement().execute(sql) }

    companion object {
        private val postgres: EmbeddedPostgres by lazy { EmbeddedPostgres.start() }
        private val counter = AtomicInteger()
    }
}
