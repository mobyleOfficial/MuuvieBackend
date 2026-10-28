package org.mobyle.data.remote.tmdb

import org.mobyle.data.remote.tmdb.model.TmdbMovieDetailResponse
import org.mobyle.data.remote.tmdb.model.TmdbWatchProviderEntry
import org.mobyle.data.remote.tmdb.model.TmdbWatchProviderRegion
import org.mobyle.data.remote.tmdb.model.TmdbWatchProvidersWrapper
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
