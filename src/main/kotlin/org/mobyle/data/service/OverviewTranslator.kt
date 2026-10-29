package org.mobyle.data.service

import kotlinx.coroutines.CancellationException
import org.mobyle.data.local.movies.MovieCatalogDataSource
import org.mobyle.data.remote.translation.TranslationDataSource
import org.mobyle.domain.model.MovieDetail
import org.slf4j.LoggerFactory

/**
 * Translates into Portuguese the overview TMDB only has in another language, once per movie:
 * a movie that already keeps a Portuguese overview (TMDB, Filmow or translated) is not sent
 * again. Without a translation service, or when it fails, the overview stays in its language.
 */
class OverviewTranslator(
    private val translationDataSource: TranslationDataSource?,
    private val catalogDataSource: MovieCatalogDataSource
) {
    private val log = LoggerFactory.getLogger(OverviewTranslator::class.java)

    suspend fun portuguese(tmdbId: Int, detail: MovieDetail): MovieDetail {
        val language = detail.overviewLanguage ?: return detail
        val translation = translationDataSource ?: return detail
        if (catalogDataSource.hasPortugueseOverview(tmdbId)) return detail
        return try {
            val overview = translation.toPortuguese(detail.overview, language)
            detail.copy(overview = overview, overviewLanguage = null, overviewTranslated = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Overview translation failed for tmdbId=$tmdbId: ${e.message}")
            detail
        }
    }
}
