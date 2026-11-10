package org.mobyle.data.imports

import org.mobyle.domain.model.ImportBundle
import org.mobyle.domain.model.ImportedList
import org.mobyle.domain.model.ImportedMovie
import org.mobyle.domain.model.vocabulary.MovieMatchMethod

/**
 * Turns classified CSVs into one bundle. Files are applied in a fixed order so that the
 * rows that add detail (ratings, diary dates) find the movies the watched files created.
 *
 * Gotchas of Letterboxd's export: `Date` is when a row was logged on Letterboxd, never when
 * the movie was watched; and the diary's `Letterboxd URI` links to the diary entry, not to the
 * film, so diary rows are joined to watched movies by title and year.
 */
internal class ImportBundleBuilder(private val acceptLetterboxdUris: Boolean) {
    private val watched = LinkedHashMap<String, ImportedMovie>()
    private val watchlist = LinkedHashMap<String, ImportedMovie>()
    private val favorites = LinkedHashMap<String, ImportedMovie>()
    private val lists = mutableListOf<ImportedList>()

    // Title key → external id of a watched movie, for the diary join.
    private val watchedByTitle = HashMap<String, String>()

    fun build(files: List<Pair<ImportCsvKind, ImportCsvFile>>): ImportBundle {
        for (kind in APPLY_ORDER) {
            files.filter { it.first == kind }.forEach { (_, file) -> apply(kind, file) }
        }
        return ImportBundle(
            watched = watched.values.toList(),
            watchlist = watchlist.values.toList(),
            favorites = favorites.values.toList(),
            lists = lists
        )
    }

    private fun apply(kind: ImportCsvKind, file: ImportCsvFile) {
        if (kind == ImportCsvKind.LIST) {
            readList(file)?.let(lists::add)
            return
        }
        val table = CsvTable(file.rows.first(), file.rows.drop(1))
        for (row in table.rows) {
            when (kind) {
                ImportCsvKind.IMPORT_FORMAT -> importFormatMovie(table, row)?.let(::mergeWatched)
                ImportCsvKind.WATCHED -> exportMovie(table, row)?.let(::mergeWatched)
                ImportCsvKind.RATINGS -> exportMovie(table, row)
                    ?.let { it.copy(stars = ImportCells.stars(table.value(row, "rating"))) }
                    ?.let(::mergeWatched)
                ImportCsvKind.DIARY -> diaryEntry(table, row)
                ImportCsvKind.REVIEWS -> diaryEntry(table, row, withReview = true)
                ImportCsvKind.WATCHLIST -> exportMovie(table, row)?.let { watchlist.putIfAbsent(it.externalId, it) }
                ImportCsvKind.LIKES -> exportMovie(table, row)?.let { favorites.putIfAbsent(it.externalId, it) }
                ImportCsvKind.LIST -> Unit
            }
        }
    }

    /** filmow_to_letterboxd writes Title (the original title when Filmow has one), Directors, Year, Rating. */
    private fun importFormatMovie(table: CsvTable, row: List<String>): ImportedMovie? {
        val tmdbId = ImportCells.tmdbId(table.value(row, "tmdbid"))
        val imdbUrl = ImportCells.imdbUrl(table.value(row, "imdbid"))
        val title = table.value(row, "title")
            ?: imdbUrl?.let { table.value(row, "imdbid") }
            ?: tmdbId?.let { "TMDB $it" }
            ?: return null
        val year = ImportCells.year(table.value(row, "year"))
        val letterboxdUri = if (acceptLetterboxdUris) ImportCells.letterboxdFilmUri(table.value(row, "letterboxduri")) else null
        return ImportedMovie(
            externalId = letterboxdUri ?: ImportedMovie.titleKey(title, year),
            title = title,
            year = year,
            director = table.value(row, "directors") ?: table.value(row, "director"),
            imdbUrl = imdbUrl,
            stars = ImportCells.stars(table.value(row, "rating")) ?: ImportCells.starsFromTen(table.value(row, "rating10")),
            watchedOn = ImportCells.date(table.value(row, "watcheddate")),
            review = table.value(row, "review"),
            tmdbId = tmdbId,
            matchMethod = tmdbId?.let { MovieMatchMethod.TMDB.wireValue }
        )
    }

    /** A row of watched, ratings, watchlist, likes or a list: Name, Year and the film's link. */
    private fun exportMovie(table: CsvTable, row: List<String>, uriColumn: String = "letterboxduri"): ImportedMovie? {
        val title = table.value(row, "name") ?: return null
        val year = ImportCells.year(table.value(row, "year"))
        return ImportedMovie(
            externalId = ImportCells.letterboxdFilmUri(table.value(row, uriColumn)) ?: ImportedMovie.titleKey(title, year),
            title = title,
            year = year
        )
    }

    /**
     * A diary entry adds a session to the watched movie of the same title and year (and rates it
     * when ratings.csv did not). A review entry does the same and brings its text; reviews.csv is
     * in date order, so the latest review of a movie wins.
     */
    private fun diaryEntry(table: CsvTable, row: List<String>, withReview: Boolean = false) {
        val title = table.value(row, "name") ?: return
        val year = ImportCells.year(table.value(row, "year"))
        val titleKey = ImportedMovie.titleKey(title, year)
        val externalId = watchedByTitle[titleKey] ?: titleKey
        val existing = watched[externalId] ?: ImportedMovie(externalId = externalId, title = title, year = year)
        val watchedOn = ImportCells.date(table.value(row, "watcheddate"))
        mergeWatched(
            existing.copy(
                watchedOn = watchedOn,
                watchDates = listOfNotNull(watchedOn),
                stars = existing.stars ?: ImportCells.stars(table.value(row, "rating")),
                review = if (withReview) table.value(row, "review") else null
            )
        )
    }

    /** Several rows of one movie (rewatches, a rating after the watch) become one, keeping the latest date. */
    private fun mergeWatched(movie: ImportedMovie) {
        val existing = watched[movie.externalId]
        watched[movie.externalId] = if (existing == null) movie else existing.copy(
            year = existing.year ?: movie.year,
            director = existing.director ?: movie.director,
            imdbUrl = existing.imdbUrl ?: movie.imdbUrl,
            stars = movie.stars ?: existing.stars,
            watchedOn = ImportCells.latest(existing.watchedOn, movie.watchedOn),
            watchDates = (existing.watchDates + listOfNotNull(existing.watchedOn, movie.watchedOn) + movie.watchDates)
                .distinct().sorted(),
            review = movie.review ?: existing.review,
            tmdbId = existing.tmdbId ?: movie.tmdbId,
            matchMethod = existing.matchMethod ?: movie.matchMethod
        )
        watchedByTitle.putIfAbsent(ImportedMovie.titleKey(movie.title, movie.year), movie.externalId)
    }

    /**
     * A list file has a preamble before its movies:
     * `Letterboxd list export v7` / `Date,Name,Tags,URL,Description` / the list / blank /
     * `Position,Name,Year,URL,Description` / the movies.
     */
    private fun readList(file: ImportCsvFile): ImportedList? {
        val rows = file.rows
        val metadataHeader = rows.indexOfFirst { row -> row.map(Csv::normalizeHeader).let { "name" in it && "url" in it && "position" !in it } }
        val metadata = metadataHeader.takeIf { it >= 0 }?.let { index ->
            CsvTable(rows[index], emptyList()) to rows.getOrNull(index + 1).orEmpty()
        }
        val itemsHeader = rows.indexOfFirst { row -> row.firstOrNull()?.let(Csv::normalizeHeader) == "position" }
        if (itemsHeader < 0) return null
        val items = CsvTable(rows[itemsHeader], rows.drop(itemsHeader + 1))
        val movies = items.rows
            .sortedBy { items.value(it, "position")?.toIntOrNull() ?: Int.MAX_VALUE }
            .mapNotNull { exportMovie(items, it, uriColumn = "url") }
            .distinctBy { it.externalId }
        if (movies.isEmpty()) return null
        val title = metadata?.let { (table, row) -> table.value(row, "name") } ?: file.fileName.removeSuffix(".csv")
        return ImportedList(
            externalId = metadata?.let { (table, row) -> table.value(row, "url") } ?: "list:${file.path}",
            title = title,
            description = metadata?.let { (table, row) -> table.value(row, "description") },
            movies = movies
        )
    }

    private companion object {
        val APPLY_ORDER = listOf(
            ImportCsvKind.WATCHED,
            ImportCsvKind.IMPORT_FORMAT,
            ImportCsvKind.RATINGS,
            ImportCsvKind.DIARY,
            ImportCsvKind.REVIEWS,
            ImportCsvKind.WATCHLIST,
            ImportCsvKind.LIKES,
            ImportCsvKind.LIST
        )
    }
}
