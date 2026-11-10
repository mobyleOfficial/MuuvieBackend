package org.mobyle.data.local.movies

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import kotlinx.datetime.Clock
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.mobyle.data.local.database.DatabaseMigrations
import org.mobyle.data.local.database.MoviesTable
import org.mobyle.domain.model.Movie
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days

/** The similar movies cache against Postgres, empty answers included (Universo de Filmes). */
class SimilarsNegativeCacheTest {

    private val catalog = MovieCatalogDataSourceImpl()

    @BeforeTest
    fun setUp() {
        val name = "similars_${counter.incrementAndGet()}"
        postgres.postgresDatabase.connection.use { it.createStatement().execute("CREATE DATABASE $name") }
        val dataSource = postgres.getDatabase("postgres", name)
        DatabaseMigrations.migrate(dataSource)
        Database.connect(dataSource)
        dataSource.connection.use {
            it.createStatement().execute("INSERT INTO movies (id, tmdb_id, title) VALUES (1, 679, 'Aliens')")
        }
    }

    @Test
    fun `a movie never checked is a miss`() {
        val cached = catalog.getSimilarMovies(679)

        assertTrue(cached.data.isEmpty())
        assertTrue(cached.isStale)
    }

    @Test
    fun `an empty answer from TMDB is kept for 7 days`() {
        catalog.saveSimilarMovies(679, emptyList())

        val fresh = catalog.getSimilarMovies(679)
        assertTrue(fresh.data.isEmpty())
        assertFalse(fresh.isStale)

        transaction {
            MoviesTable.update({ MoviesTable.tmdbId eq 679 }) {
                it[similarsCheckedAt] = Clock.System.now() - 8.days
            }
        }
        assertTrue(catalog.getSimilarMovies(679).isStale)
    }

    @Test
    fun `similars saved are answered as before`() {
        catalog.saveSimilarMovies(679, listOf(Movie(id = 0, tmdbId = 348, title = "Alien")))

        val cached = catalog.getSimilarMovies(679)
        assertEquals(listOf(348), cached.data.map { it.tmdbId })
        assertFalse(cached.isStale)
    }

    companion object {
        private val postgres: EmbeddedPostgres = EmbeddedPostgres.start()
        private val counter = AtomicInteger()
    }
}
