package org.mobyle.data.local.profile

import io.mockk.mockk
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.jetbrains.exposed.sql.Database
import org.mobyle.data.local.database.DatabaseMigrations
import org.mobyle.data.local.locadoras.LocadoraDataSourceImpl
import org.mobyle.data.local.shelves.ShelfDataSourceImpl
import org.mobyle.data.local.user.UserDatabaseDataSourceImpl
import org.mobyle.domain.model.HighlightDraft
import org.mobyle.domain.model.LocadoraDraft
import org.mobyle.domain.model.PageRequest
import org.mobyle.domain.model.ProfileDraft
import org.mobyle.domain.model.ShelfDraft
import org.mobyle.domain.model.User
import org.mobyle.domain.model.vocabulary.HighlightType
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

class UserProfileDataSourceImplTest {

    private lateinit var dataSource: DataSource
    private val profiles = UserProfileDataSourceImpl()
    private val shelves = ShelfDataSourceImpl()
    private val locadoras = LocadoraDataSourceImpl(shelves)
    private val users = UserDatabaseDataSourceImpl()
    private val everyone = Visibility.entries.toSet()
    private val publicOnly = setOf(Visibility.PUBLIC)

    @BeforeTest
    fun setUp() {
        val name = "profile_${counter.incrementAndGet()}"
        postgres.postgresDatabase.connection.use { it.createStatement().execute("CREATE DATABASE $name") }
        dataSource = postgres.getDatabase("postgres", name)
        DatabaseMigrations.migrate(dataSource)
        Database.connect(dataSource)

        users.save(User(id = "ana", email = "ana@x.com", username = "ana", avatar = null, bio = null, createdAt = ""))
        users.save(User(id = "bia", email = "bia@x.com", username = "bia", avatar = null, bio = null, createdAt = ""))
        execute("INSERT INTO movies (id, tmdb_id, title, \"year\", poster_path) VALUES (10, 100, 'Halloween', 1978, '/h.jpg'), (11, 101, 'Suspiria', 1977, NULL);")
    }

    @Test
    fun `saving the profile writes every field and the handle can be found`() {
        profiles.updateProfile("ana", draft(handle = "ana.cinefila"))

        val record = profiles.getProfile("ana")!!
        assertEquals("Ana", record.displayName)
        assertEquals("ana.cinefila", record.handle)
        assertEquals(listOf("https://ana.example"), record.links)
        assertEquals("#FF5500", record.accentColor)
        assertEquals("ana", profiles.findUserIdByHandle("ana.cinefila"))
        assertTrue(profiles.isHandleTaken("ana.cinefila", exceptUserId = "bia"))
        assertFalse(profiles.isHandleTaken("ana.cinefila", exceptUserId = "ana"))
    }

    @Test
    fun `a new account gets its username as the handle when it fits and is free`() {
        users.save(User(id = "c1", email = "c1@x.com", username = "Cinefila", avatar = null, bio = null, createdAt = ""))
        users.save(User(id = "c2", email = "c2@x.com", username = "CINEFILA", avatar = null, bio = null, createdAt = ""))

        assertEquals("cinefila", profiles.getProfile("c1")!!.handle)
        assertNull(profiles.getProfile("c2")!!.handle)
        assertNull(profiles.getProfile("ana")!!.handle) // too short
    }

    @Test
    fun `a null handle keeps the current one`() {
        profiles.updateProfile("ana", draft(handle = "ana.cinefila"))

        profiles.updateProfile("ana", draft(handle = null).copy(displayName = null))

        val record = profiles.getProfile("ana")!!
        assertEquals("ana.cinefila", record.handle)
        assertNull(record.displayName)
    }

    @Test
    fun `stats only count what the viewer can see`() {
        shelves.createShelf("ana", ShelfDraft("Public", null, null, Visibility.PUBLIC))
        shelves.createShelf("ana", ShelfDraft("Secret", null, null, Visibility.SECRET))
        locadoras.createLocadora("ana", LocadoraDraft("Loc", null, Visibility.MUTUAL, false, false))
        shelves.addMovie(system(SystemShelfType.WATCHED), 10)
        execute(
            "INSERT INTO user_follows (follower_id, followed_id, created_at) " +
                "SELECT b.id, a.id, now() FROM users a, users b WHERE a.external_id = 'ana' AND b.external_id = 'bia'"
        )

        val forOwner = profiles.stats("ana", everyone)
        assertEquals(4, forOwner.shelves) // the two system shelves too
        assertEquals(1, forOwner.locadoras)
        assertEquals(1, forOwner.watched)
        assertEquals(1, forOwner.followers)
        assertEquals(0, forOwner.following)

        val forOthers = profiles.stats("ana", publicOnly)
        assertEquals(3, forOthers.shelves)
        assertEquals(0, forOthers.locadoras)
        assertEquals(1, forOthers.watched)
    }

    @Test
    fun `the watched count follows the visibility of Ja assisti`() {
        shelves.addMovie(system(SystemShelfType.WATCHED), 10)
        execute("UPDATE shelves SET visibility = 'secret' WHERE system_type = 'watched'")

        assertEquals(0, profiles.stats("ana", publicOnly).watched)
        assertEquals(1, profiles.stats("ana", everyone).watched)
    }

    @Test
    fun `stats leave out the shelves a locadora hides from the profile, for the owner too`() {
        val hidden = shelves.createShelf("ana", ShelfDraft("Hidden", null, null, Visibility.PUBLIC))
        val locadora = locadoras.createLocadora("ana", LocadoraDraft("Loc", null, Visibility.PUBLIC, true, false))
        locadoras.setShelves(locadora.id, listOf(hidden.id))

        assertEquals(2, profiles.stats("ana", everyone).shelves) // only the two system shelves
        assertEquals(2, profiles.stats("ana", publicOnly).shelves)
    }

    @Test
    fun `highlights are replaced as a whole and keep the order`() {
        profiles.saveHighlights(
            "ana",
            listOf(
                HighlightDraft(HighlightType.SOUNDTRACK, enabled = false, soundtrackUrl = "https://open.spotify.com/track/1"),
                HighlightDraft(HighlightType.TOP4, enabled = true, movieIds = listOf(11, 10))
            )
        )
        profiles.saveHighlights("ana", listOf(HighlightDraft(HighlightType.TOP4, enabled = true, movieIds = listOf(10))))

        val highlights = profiles.getHighlights("ana")
        assertEquals(listOf(HighlightType.TOP4), highlights.map { it.type })
        assertEquals(listOf(10L), highlights.single().movieIds)
    }

    @Test
    fun `movie cards come in the order asked and skip unknown ids`() {
        val cards = profiles.movieCards(listOf(11, 99, 10))

        assertEquals(listOf(11L, 10L), cards.map { it.movieId })
        assertEquals(1978, cards.last().year)
    }

    private fun draft(handle: String?) = ProfileDraft(
        displayName = "Ana",
        handle = handle,
        pronouns = "ela/dela",
        bio = "Terror e giallo",
        links = listOf("https://ana.example"),
        accentColor = "#FF5500",
        avatarUrl = null,
        bannerUrl = null
    )

    private fun system(type: SystemShelfType) =
        shelves.getUserShelves("ana", everyone, PageRequest(1, 50)).results.single { it.systemType == type }

    private fun execute(sql: String) = dataSource.connection.use { it.createStatement().execute(sql) }

    companion object {
        private val postgres: EmbeddedPostgres by lazy { EmbeddedPostgres.start() }
        private val counter = AtomicInteger()
    }
}
