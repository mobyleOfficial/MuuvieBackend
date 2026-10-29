package org.mobyle.data.service

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.mobyle.data.local.database.DatabaseMigrations
import org.mobyle.data.local.database.MoviesTable
import org.mobyle.data.local.movies.MovieCatalogDataSourceImpl
import org.mobyle.data.remote.tmdb.TmdbDataSource
import org.mobyle.domain.model.Movie
import org.mobyle.domain.model.MovieResolution
import org.mobyle.domain.model.vocabulary.MovieMatchStatus
import org.mobyle.domain.repository.MovieResolver
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MovieEnrichmentServiceTest {

    private val catalog = MovieCatalogDataSourceImpl()
    private val tmdb = mockk<TmdbDataSource>()
    private val resolver = mockk<MovieResolver>()
    private val service = MovieEnrichmentService(catalog, tmdb, resolver, OverviewTranslator(null, catalog))

    @BeforeTest
    fun setUp() {
        val name = "enrichment_${counter.incrementAndGet()}"
        postgres.postgresDatabase.connection.use { it.createStatement().execute("CREATE DATABASE $name") }
        val dataSource = postgres.getDatabase("postgres", name)
        DatabaseMigrations.migrate(dataSource)
        Database.connect(dataSource)
    }

    private fun placeholder(i: Int): Long = catalog.upsertMovie(Movie(title = "Filme Raro $i"), filmowId = "f-$i")

    private fun status(dbId: Long) = transaction {
        MoviesTable.selectAll().where { MoviesTable.id eq dbId }.first()[MoviesTable.matchStatus]
    }

    @Test
    fun `unmatchable movies leave the queue instead of blocking the ones behind them`() = runBlocking {
        val stuck = (1..50).map(::placeholder)
        val behind = placeholder(51)
        coEvery { resolver.resolve(any()) } returns MovieResolution.Unmatched(emptyList())

        assertEquals(50, service.processBatch())

        assertTrue(stuck.all { status(it) == MovieMatchStatus.UNMATCHED.wireValue })
        assertEquals(listOf(behind), catalog.getMoviesNeedingEnrichment().map { it.dbId })
    }

    @Test
    fun `a TMDB error waits for the backoff before the movie is retried`() = runBlocking {
        val failing = placeholder(1)
        coEvery { resolver.resolve(any()) } throws IllegalStateException("TMDB down")

        service.processBatch()

        assertEquals(MovieMatchStatus.PENDING.wireValue, status(failing))
        assertEquals(emptyList(), catalog.getMoviesNeedingEnrichment())
        val attempts = transaction { MoviesTable.selectAll().where { MoviesTable.id eq failing }.first()[MoviesTable.enrichmentAttempts] }
        assertEquals(1, attempts)
    }

    @Test
    fun `opening an unmatched movie again does not search again`() = runBlocking {
        val movie = placeholder(1)
        catalog.markUnmatched(movie)

        assertEquals(MovieMatchStatus.UNMATCHED, service.resolveNow(movie))
        coVerify(exactly = 0) { resolver.resolve(any()) }
    }

    @Test
    fun `the detail of an unmatched placeholder says so`() {
        val movie = placeholder(1)
        catalog.markUnmatched(movie)

        assertEquals(MovieMatchStatus.UNMATCHED, catalog.getLocalMovieDetailByDbId(movie)?.matchStatus)
    }

    @Test
    fun `only movies with a TMDB id or an IMDb link are resolvable without the Filmow page`() {
        placeholder(1)
        catalog.upsertMovie(Movie(tmdbId = 10, title = "Casablanca"), filmowId = "f-2")
        val withImdb = placeholder(3)
        transaction {
            MoviesTable.update({ MoviesTable.id eq withImdb }) {
                it[imdbUrl] = "https://www.imdb.com/title/tt0034583/"
            }
        }

        assertEquals(setOf("f-2", "f-3"), catalog.getResolvableFilmowIds())
        assertEquals(MovieMatchStatus.MATCHED.wireValue, status(catalog.findByTmdbId(10)!!.id))
    }

    companion object {
        private val postgres: EmbeddedPostgres by lazy { EmbeddedPostgres.start() }
        private val counter = AtomicInteger()
    }
}
