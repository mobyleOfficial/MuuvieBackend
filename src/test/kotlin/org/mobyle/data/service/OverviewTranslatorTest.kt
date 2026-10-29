package org.mobyle.data.service

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.Database
import org.mobyle.data.local.database.DatabaseMigrations
import org.mobyle.data.local.movies.MovieCatalogDataSourceImpl
import org.mobyle.data.remote.translation.TranslationDataSource
import org.mobyle.domain.model.Movie
import org.mobyle.domain.model.MovieDetail
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OverviewTranslatorTest {

    private val catalog = MovieCatalogDataSourceImpl()
    private val deepL = mockk<TranslationDataSource>()
    private val translator = OverviewTranslator(deepL, catalog)

    private val english = MovieDetail(tmdbId = 1035705, title = "8 Found Dead", overview = "Two couples", overviewLanguage = "en")

    @BeforeTest
    fun setUp() {
        val name = "translator_${counter.incrementAndGet()}"
        postgres.postgresDatabase.connection.use { it.createStatement().execute("CREATE DATABASE $name") }
        val dataSource = postgres.getDatabase("postgres", name)
        DatabaseMigrations.migrate(dataSource)
        Database.connect(dataSource)
    }

    private fun enrich(detail: MovieDetail) = runBlocking {
        catalog.enrichMovie(1035705, translator.portuguese(1035705, detail), null)
    }

    private fun kept() = catalog.getLocalMovieDetail(1035705)!!

    @Test
    fun `an English overview is translated and kept marked as translated`() {
        catalog.upsertMovie(Movie(tmdbId = 1035705, title = "8 Found Dead"))
        coEvery { deepL.toPortuguese("Two couples", "en") } returns "Dois casais"

        enrich(english)

        assertEquals("Dois casais", kept().overview)
        assertTrue(kept().overviewTranslated)
        assertNull(kept().overviewLanguage)
    }

    @Test
    fun `a translated overview is not translated again`() {
        catalog.upsertMovie(Movie(tmdbId = 1035705, title = "8 Found Dead"))
        coEvery { deepL.toPortuguese(any(), any()) } returns "Dois casais"

        enrich(english)
        enrich(english)

        coVerify(exactly = 1) { deepL.toPortuguese(any(), any()) }
        assertEquals("Dois casais", kept().overview)
    }

    @Test
    fun `a Portuguese overview from Filmow is neither translated nor replaced`() {
        catalog.upsertMovie(Movie(tmdbId = 1035705, title = "8 Found Dead", overview = "Dois casais vão ao deserto"))

        enrich(english)

        coVerify(exactly = 0) { deepL.toPortuguese(any(), any()) }
        assertEquals("Dois casais vão ao deserto", kept().overview)
        assertFalse(kept().overviewTranslated)
    }

    @Test
    fun `an official pt-BR overview replaces the translated one`() {
        catalog.upsertMovie(Movie(tmdbId = 1035705, title = "8 Found Dead"))
        coEvery { deepL.toPortuguese(any(), any()) } returns "Dois casais"
        enrich(english)

        enrich(english.copy(overview = "Dois casais oficiais", overviewLanguage = null))

        assertEquals("Dois casais oficiais", kept().overview)
        assertFalse(kept().overviewTranslated)
    }

    @Test
    fun `when DeepL fails the English overview stays, to be translated later`() {
        catalog.upsertMovie(Movie(tmdbId = 1035705, title = "8 Found Dead"))
        coEvery { deepL.toPortuguese(any(), any()) } throws IllegalStateException("quota")

        enrich(english)

        assertEquals("Two couples", kept().overview)
        assertEquals("en", kept().overviewLanguage)
        assertFalse(catalog.hasPortugueseOverview(1035705))
    }

    companion object {
        private val postgres: EmbeddedPostgres by lazy { EmbeddedPostgres.start() }
        private val counter = AtomicInteger()
    }
}
