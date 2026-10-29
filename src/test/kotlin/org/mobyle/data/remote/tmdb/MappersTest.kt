package org.mobyle.data.remote.tmdb

import org.mobyle.data.remote.tmdb.model.TmdbMovieDetailResponse
import org.mobyle.data.remote.tmdb.model.TmdbTranslation
import org.mobyle.data.remote.tmdb.model.TmdbTranslationData
import org.mobyle.data.remote.tmdb.model.TmdbTranslations
import org.mobyle.data.remote.tmdb.model.TmdbWatchProviderEntry
import org.mobyle.data.remote.tmdb.model.TmdbWatchProviderRegion
import org.mobyle.data.remote.tmdb.model.TmdbWatchProvidersWrapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MappersTest {

    private fun detail(vararg regions: Pair<String, List<String>>) = TmdbMovieDetailResponse(
        id = 539,
        title = "Psicose",
        watchProviders = TmdbWatchProvidersWrapper(
            results = regions.associate { (region, names) ->
                region to TmdbWatchProviderRegion(flatrate = names.map { TmdbWatchProviderEntry(providerName = it) })
            }
        )
    )

    private fun withOverviews(overview: String, vararg translations: Triple<String, String, String>) =
        TmdbMovieDetailResponse(
            id = 1035705,
            title = "8 Found Dead",
            overview = overview,
            translations = TmdbTranslations(
                translations.map { (language, country, text) ->
                    TmdbTranslation(language = language, country = country, data = TmdbTranslationData(text))
                }
            )
        )

    @Test
    fun `where to watch lists only the Brazilian services`() {
        val movie = detail(
            "BR" to listOf("HBO Max", "Telecine Amazon Channel"),
            "AU" to listOf("BINGE", "Foxtel Now"),
            "CA" to listOf("Crave")
        ).toDomain()

        assertEquals(listOf("HBO Max", "Telecine Amazon Channel"), movie.watchProviders.map { it.name })
    }

    @Test
    fun `no Brazilian services means an empty list, not another country's`() {
        val movie = detail("AU" to listOf("BINGE")).toDomain()

        assertTrue(movie.watchProviders.isEmpty())
    }

    @Test
    fun `the pt-BR overview wins and has no language mark`() {
        val movie = withOverviews("Dois casais", Triple("en", "US", "Two couples")).toDomain()

        assertEquals("Dois casais", movie.overview)
        assertNull(movie.overviewLanguage)
    }

    @Test
    fun `without a pt-BR overview the en-US one fills it, marked as English`() {
        val movie = withOverviews(
            "",
            Triple("en", "GB", "Two couples (GB)"),
            Triple("en", "US", "Two couples"),
            Triple("de", "DE", "Zwei Paare")
        ).toDomain()

        assertEquals("Two couples", movie.overview)
        assertEquals("en", movie.overviewLanguage)
    }

    @Test
    fun `a Portuguese overview from another country comes before the English one`() {
        val movie = withOverviews("", Triple("en", "US", "Two couples"), Triple("pt", "PT", "Dois casais")).toDomain()

        assertEquals("Dois casais", movie.overview)
        assertNull(movie.overviewLanguage)
    }

    @Test
    fun `no overview in any language stays empty`() {
        val movie = withOverviews("", Triple("de", "DE", "Zwei Paare")).toDomain()

        assertEquals("", movie.overview)
        assertNull(movie.overviewLanguage)
    }
}
