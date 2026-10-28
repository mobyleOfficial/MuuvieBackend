package org.mobyle.data.local.imports

import io.mockk.mockk
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import kotlinx.datetime.LocalDate
import org.jetbrains.exposed.sql.Database
import org.mobyle.data.local.database.DatabaseMigrations
import org.mobyle.data.local.movies.MovieCatalogDataSourceImpl
import org.mobyle.data.local.shelves.ShelfDataSourceImpl
import org.mobyle.data.local.user.UserDatabaseDataSourceImpl
import org.mobyle.data.local.watch.WatchDataSourceImpl
import org.mobyle.domain.model.ImportBundle
import org.mobyle.domain.model.ImportJob
import org.mobyle.domain.model.ImportOptions
import org.mobyle.domain.model.ImportedList
import org.mobyle.domain.model.ImportedMovie
import org.mobyle.domain.model.MovieCandidate
import org.mobyle.domain.model.PageRequest
import org.mobyle.domain.model.ShelfDraft
import org.mobyle.domain.model.ShelfMovieFilter
import org.mobyle.domain.model.User
import org.mobyle.domain.model.vocabulary.ImportSource
import org.mobyle.domain.model.vocabulary.ImportStatus
import org.mobyle.domain.model.vocabulary.Rating
import org.mobyle.domain.model.vocabulary.SystemShelfType
import org.mobyle.domain.model.vocabulary.Visibility
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImportDataSourceImplTest {

    private lateinit var dataSource: DataSource
    private val imports = ImportDataSourceImpl(MovieCatalogDataSourceImpl())
    private val shelves = ShelfDataSourceImpl()
    private val watch = WatchDataSourceImpl()
    private val users = UserDatabaseDataSourceImpl()
    private val page = PageRequest(1, 50)
    private val everyone = Visibility.entries.toSet()

    private val casablanca = ImportedMovie("f-1", "Casablanca", year = 1942, tmdbId = 100, stars = 4.5, watchedOn = LocalDate(2020, 5, 1))
    private val vertigo = ImportedMovie("f-2", "Vertigo", year = 1958, tmdbId = 101)
    private val psycho = ImportedMovie("f-3", "Psicose", originalTitle = "Psycho", year = 1960, tmdbId = 102)
    private val obscure = ImportedMovie("f-4", "Filme Raro", year = 1971)

    private val bundle = ImportBundle(
        watched = listOf(casablanca, obscure),
        watchlist = listOf(vertigo, casablanca),
        favorites = listOf(psycho),
        lists = listOf(
            ImportedList("l-1", "Hitchcock", "Os melhores", "https://cover/1.jpg", listOf(vertigo, psycho)),
            ImportedList("l-2", "Noir", movies = listOf(casablanca))
        )
    )

    @BeforeTest
    fun setUp() {
        // One embedded server per class, one fresh database per test.
        val name = "imports_${counter.incrementAndGet()}"
        postgres.postgresDatabase.connection.use { it.createStatement().execute("CREATE DATABASE $name") }
        dataSource = postgres.getDatabase("postgres", name)
        DatabaseMigrations.migrate(dataSource)
        Database.connect(dataSource)

        users.save(User(id = "ana", email = "ana@x.com", username = "ana", avatar = null, bio = null, createdAt = ""))
    }

    @Test
    fun `importing the same bundle twice leaves the same state`() {
        val first = runImport(bundle)
        val snapshot = state()

        val second = runImport(bundle)

        assertEquals(first, second)
        assertEquals(snapshot, state())
        assertEquals(1L, count("SELECT count(*) FROM shelves WHERE import_source = 'filmow' AND external_id = 'l-1'"))
        assertEquals(1L, count("SELECT count(*) FROM watch_logs"))
    }

    @Test
    fun `movies land on the system shelves and watched wins over the watchlist`() {
        val counts = runImport(bundle)

        assertEquals(setOf(100, 102, null), tmdbIds(SystemShelfType.WATCHED))
        assertEquals(setOf(101), tmdbIds(SystemShelfType.WATCHLIST))
        assertEquals(2, counts.watched)
        assertEquals(1, counts.logs)
        val casablancaRow = watch.getUserMovie("ana", movieId(100))!!
        assertEquals(Rating.LOVED, casablancaRow.rating)
        assertEquals(100, casablancaRow.progressPercent)
        assertEquals(Rating.FAVORITE, watch.getUserMovie("ana", movieId(102))!!.rating)
    }

    @Test
    fun `lists become shelves with the source id, cover and chosen visibility`() {
        runImport(bundle, ImportOptions(listsToImport = listOf("l-1"), defaultVisibility = Visibility.SECRET))

        val imported = shelves.getUserShelves("ana", everyone, page).results.filter { it.systemType == null }
        assertEquals(listOf("Hitchcock"), imported.map { it.name })
        assertEquals(Visibility.SECRET, imported.single().visibility)
        assertEquals("https://cover/1.jpg", query("SELECT cover_url FROM shelves WHERE external_id = 'l-1'"))
        assertEquals(listOf(101L, 102L).map { movieId(it.toInt()) }, shelfMovieIds(imported.single().id))
    }

    @Test
    fun `data created in the Muuvie is never overwritten`() {
        execute("INSERT INTO movies (id, tmdb_id, title) VALUES (50, 100, 'Casablanca');")
        watch.setRating("ana", 50, Rating.ON_HOLD, "meh", false)

        val counts = runImport(ImportBundle(watched = listOf(casablanca), favorites = listOf(casablanca)))

        val row = watch.getUserMovie("ana", 50)!!
        assertEquals(Rating.ON_HOLD, row.rating)
        assertEquals("none", row.status)
        assertEquals(2, counts.keptFromMuuvie)
        // The dated session is history and is added anyway.
        assertEquals(1L, count("SELECT count(*) FROM watch_logs WHERE movie_id = 50"))
    }

    @Test
    fun `a list edited in the Muuvie only gains movies on a reimport`() {
        runImport(ImportBundle(lists = listOf(ImportedList("l-1", "Hitchcock", movies = listOf(vertigo)))))
        val shelf = shelves.getUserShelves("ana", everyone, page).results.first { it.systemType == null }
        shelves.updateShelf(shelf.id, ShelfDraft("Mestre do suspense", null, null, Visibility.PUBLIC))
        execute("INSERT INTO movies (id, tmdb_id, title) VALUES (60, 200, 'Rear Window');")
        shelves.addMovie(shelf, 60)

        runImport(ImportBundle(lists = listOf(ImportedList("l-1", "Hitchcock", movies = listOf(psycho)))))

        val after = shelves.getUserShelves("ana", everyone, page).results.first { it.id == shelf.id }
        assertEquals("Mestre do suspense", after.name)
        assertEquals(listOf(movieId(101), 60L, movieId(102)), shelfMovieIds(shelf.id))
    }

    @Test
    fun `unmatched movies are imported and queued once per user and source`() {
        val candidates = listOf(MovieCandidate(300, "Filme Raro", year = 1972, score = 0.85))

        val counts = runImport(bundle, unmatched = mapOf("f-4" to candidates))
        runImport(bundle, unmatched = mapOf("f-4" to candidates))

        assertEquals(1, counts.unmatched)
        assertEquals(1L, count("SELECT count(*) FROM import_unmatched"))
        assertTrue(tmdbIds(SystemShelfType.WATCHED).contains(null))
    }

    @Test
    fun `confirming an unmatched movie merges it with the TMDB movie, logs included`() {
        val dated = obscure.copy(watchedOn = LocalDate(2021, 1, 2))
        val job = runJob(ImportBundle(watched = listOf(dated)), unmatched = mapOf("f-4" to emptyList()))
        execute("INSERT INTO movies (id, tmdb_id, title) VALUES (70, 300, 'Rare Movie');")
        val item = imports.getUnmatched(job.id, page).results.single()

        val resolved = imports.matchUnmatched(item, ImportSource.FILMOW, 300, "Rare Movie")

        assertEquals("matched", resolved.resolution)
        assertEquals(70L, resolved.movieId)
        assertEquals(setOf(300), tmdbIds(SystemShelfType.WATCHED))
        assertEquals(1L, count("SELECT count(*) FROM watch_logs WHERE movie_id = 70"))
        assertEquals("f-4", query("SELECT filmow_id FROM movies WHERE id = 70"))
    }

    @Test
    fun `one active job per user and source, and a restart keeps only jobs waiting on the preview`() {
        val reading = imports.createJob("ana", ImportSource.FILMOW, "ana")!!
        assertNull(imports.createJob("ana", ImportSource.FILMOW, "ana"))
        assertNotNull(imports.createJob("ana", ImportSource.LETTERBOXD, "ana"))

        imports.transition(reading.id, setOf(ImportStatus.PENDING), ImportStatus.READING, null)
        assertTrue(imports.hasRunningJob("ana"))
        imports.saveRead(reading.id, bundle)
        imports.transition(reading.id + 1, setOf(ImportStatus.PENDING), ImportStatus.CANCELLED, null)
        assertFalse(imports.hasRunningJob("ana"))
        assertEquals(0, imports.failInterrupted())

        val job = imports.getJob(reading.id)!!
        assertEquals(ImportStatus.PREVIEW, job.status)
        assertEquals(2, job.preview?.lists?.size)
        assertEquals(1, job.preview?.logs)
    }

    @Test
    fun `cancelling at the preview writes nothing and drops the bundle`() {
        val job = imports.createJob("ana", ImportSource.FILMOW, "ana")!!
        imports.transition(job.id, setOf(ImportStatus.PENDING), ImportStatus.READING, null)
        imports.saveRead(job.id, bundle)

        assertTrue(imports.transition(job.id, setOf(ImportStatus.PREVIEW), ImportStatus.CANCELLED, null))

        assertNull(imports.getBundle(job.id))
        assertFalse(imports.confirm(job.id, ImportOptions()))
        assertEquals(0L, count("SELECT count(*) FROM user_movies"))
        assertEquals(0L, count("SELECT count(*) FROM shelves WHERE system_type IS NULL"))
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun runImport(
        bundle: ImportBundle,
        options: ImportOptions = ImportOptions(),
        unmatched: Map<String, List<MovieCandidate>> = emptyMap()
    ) = imports.getJob(runJob(bundle, options, unmatched).id)!!.counts!!

    private fun runJob(
        bundle: ImportBundle,
        options: ImportOptions = ImportOptions(),
        unmatched: Map<String, List<MovieCandidate>> = emptyMap()
    ): ImportJob {
        val job = imports.createJob("ana", ImportSource.FILMOW, "ana")!!
        imports.transition(job.id, setOf(ImportStatus.PENDING), ImportStatus.READING, null)
        imports.saveRead(job.id, bundle)
        imports.confirm(job.id, options)
        val confirmed = imports.getJob(job.id)!!
        val counts = imports.write(confirmed, bundle, options, unmatched) { _, _ -> }
        imports.finish(job.id, counts)
        return confirmed
    }

    private fun state(): List<Any?> = listOf(
        query("SELECT string_agg(movie_id || ':' || status || ':' || coalesce(rating::text, '-'), ',' ORDER BY movie_id) FROM user_movies"),
        query("SELECT string_agg(shelf_id || ':' || movie_id, ',' ORDER BY shelf_id, movie_id) FROM shelf_items"),
        query("SELECT string_agg(name, ',' ORDER BY id) FROM shelves"),
        query("SELECT string_agg(movie_id || ':' || watched_on, ',' ORDER BY id) FROM watch_logs"),
        count("SELECT count(*) FROM movies")
    )

    private fun system(type: SystemShelfType) =
        shelves.getUserShelves("ana", everyone, page).results.first { it.systemType == type }

    private fun tmdbIds(type: SystemShelfType): Set<Int?> =
        shelves.getShelfMovies(system(type), ShelfMovieFilter(), page).results.map { it.tmdbId }.toSet()

    private fun shelfMovieIds(shelfId: Long): List<Long> {
        val shelf = shelves.getUserShelves("ana", everyone, page).results.first { it.id == shelfId }
        return shelves.getShelfMovies(shelf, ShelfMovieFilter(), page).results.map { it.movieId }
    }

    private fun movieId(tmdbId: Int): Long = count("SELECT id FROM movies WHERE tmdb_id = $tmdbId")

    private fun execute(sql: String) = dataSource.connection.use { it.createStatement().execute(sql) }

    private fun query(sql: String): Any? = dataSource.connection.use { connection ->
        connection.createStatement().use { stmt -> stmt.executeQuery(sql).use { rs -> rs.next(); rs.getObject(1) } }
    }

    private fun count(sql: String): Long = (query(sql) as Number).toLong()

    companion object {
        private val postgres: EmbeddedPostgres by lazy { EmbeddedPostgres.start() }
        private val counter = AtomicInteger()
    }
}
