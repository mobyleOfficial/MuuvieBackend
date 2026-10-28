package org.mobyle.data.repository

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.mobyle.data.local.movies.MovieCatalogDataSource
import org.mobyle.data.remote.filmow.FilmowDataSource
import org.mobyle.domain.model.FilmowMoviePartial
import org.mobyle.domain.model.FilmowProfile
import org.mobyle.domain.model.ImportBundle
import org.mobyle.domain.model.ImportedList
import org.mobyle.domain.model.ImportedMovie
import org.mobyle.domain.model.Movie
import org.mobyle.domain.model.vocabulary.ImportSource
import org.mobyle.domain.policy.MovieMatchPolicy
import org.mobyle.domain.repository.ImportSourceReader

/**
 * F3.2: reads a Filmow profile with the scraper and maps it to the neutral bundle. Movies the
 * catalog already resolves skip their Filmow detail page (filmow-movie-enrichment).
 */
class FilmowImportReader(
    private val filmowDataSource: FilmowDataSource,
    private val catalog: MovieCatalogDataSource
) : ImportSourceReader {
    override val source = ImportSource.FILMOW

    override suspend fun read(inputRef: String, credentials: String): ImportBundle =
        toBundle(filmowDataSource.scrapeProfile(credentials, inputRef, catalog.getResolvableFilmowIds()))

    companion object {
        /** "Recently watched" holds the same movies as "watched", sometimes with the date. */
        fun toBundle(profile: FilmowProfile): ImportBundle {
            val details = profile.filmowDetails
            val watched = (profile.watched + profile.recentlyWatched)
                .map { it.toImported(details) }
                .groupBy { it.externalId }
                .map { (_, same) -> same.firstOrNull { it.watchedOn != null } ?: same.first() }
            return ImportBundle(
                watched = watched,
                watchlist = profile.watchlist.map { it.toImported(details) }.distinctBy { it.externalId },
                favorites = profile.favorites.map { it.toImported(details) }.distinctBy { it.externalId },
                lists = profile.lists.map { list ->
                    ImportedList(
                        externalId = list.filmowId,
                        title = list.title,
                        description = list.description,
                        coverUrl = list.coverUrl,
                        movies = list.movies.map { it.toImported(details) }.distinctBy { it.externalId }
                    )
                }
            )
        }

        private fun Movie.toImported(details: Map<String, FilmowMoviePartial>): ImportedMovie {
            val partial = filmowId?.let { details[it] }
            val year = releaseDate?.take(4)?.toIntOrNull()
            return ImportedMovie(
                // Without a Filmow id (rare), the title and year identify the movie on reimports.
                externalId = filmowId ?: "title:${MovieMatchPolicy.normalize(title)}:${year ?: ""}",
                title = title,
                originalTitle = originalTitle,
                localTitle = localTitle,
                year = year,
                posterUrl = posterPath,
                overview = overview.takeIf { it.isNotBlank() },
                releaseDate = releaseDate,
                runtime = partial?.runtime,
                director = partial?.director,
                genres = partial?.genres.orEmpty(),
                imdbUrl = partial?.imdbUrl,
                stars = userRating,
                watchedOn = watchedAt?.let(::parseDate)
            )
        }

        private fun parseDate(value: String): LocalDate? =
            runCatching { Instant.parse(value).toLocalDateTime(TimeZone.UTC).date }.getOrNull()
                ?: runCatching { LocalDate.parse(value.take(10)) }.getOrNull()
    }
}
