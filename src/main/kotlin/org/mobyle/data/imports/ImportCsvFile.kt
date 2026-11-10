package org.mobyle.data.imports

import kotlinx.datetime.LocalDate

/** What a CSV of an import holds, told by its header (and, when the header is ambiguous, its name). */
enum class ImportCsvKind {
    /** Letterboxd's import format, which filmow_to_letterboxd writes: Title, Directors, Year, Rating… */
    IMPORT_FORMAT,
    WATCHED,
    RATINGS,
    DIARY,
    WATCHLIST,
    LIKES,
    LIST
}

/** A parsed CSV: [path] is the file name, or its path inside the zip when [fromZip]. */
class ImportCsvFile(val path: String, val rows: List<List<String>>, val fromZip: Boolean = false) {
    val fileName: String get() = path.substringAfterLast('/')

    /** Rows with something in them; blank lines and the header are not counted. */
    val dataRowCount: Int get() = rows.drop(1).count { row -> row.any { it.isNotBlank() } }

    fun isBlank(): Boolean = rows.all { row -> row.all { it.isBlank() } }
}

/** A header row with its columns looked up by Csv.normalizeHeader. */
class CsvTable(header: List<String>, val rows: List<List<String>>) {
    private val columns: Map<String, Int> = header
        .mapIndexed { index, name -> Csv.normalizeHeader(name) to index }
        .toMap()

    fun has(column: String): Boolean = column in columns

    /** The trimmed value, or null when the column is missing or the cell is blank. */
    fun value(row: List<String>, column: String): String? =
        columns[column]?.let { row.getOrNull(it) }?.trim()?.takeIf { it.isNotEmpty() }
}

/** Cell parsing shared by the Filmow and Letterboxd files. Bad values become null, never errors. */
internal object ImportCells {
    private val IMDB_ID = Regex("tt\\d{5,10}")
    private const val FIRST_MOVIE_YEAR = 1870
    private const val LAST_MOVIE_YEAR = 2100
    private const val MIN_STARS = 0.5
    private const val MAX_STARS = 5.0

    fun year(value: String?): Int? = value?.toIntOrNull()?.takeIf { it in FIRST_MOVIE_YEAR..LAST_MOVIE_YEAR }

    /** 0.5 to 5 in half steps, as both sources rate. */
    fun stars(value: String?): Double? =
        value?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it in MIN_STARS..MAX_STARS && (it * 2) % 1.0 == 0.0 }

    /** Letterboxd's Rating10 column: 1 to 10, halved to stars. */
    fun starsFromTen(value: String?): Double? = value?.toIntOrNull()?.takeIf { it in 1..10 }?.let { it / 2.0 }

    fun date(value: String?): LocalDate? = value?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }

    fun tmdbId(value: String?): Int? = value?.toIntOrNull()?.takeIf { it > 0 }

    fun imdbUrl(value: String?): String? =
        value?.let { IMDB_ID.find(it)?.value }?.let { "https://www.imdb.com/title/$it/" }

    /**
     * A link to a film on Letterboxd: the short boxd.it link of the export or a /film/ page.
     * Diary and review links (letterboxd.com/<user>/film/<slug>/) point to an entry, not a film.
     */
    fun letterboxdFilmUri(value: String?): String? {
        val uri = value?.trim()?.removeSuffix("/") ?: return null
        val path = uri.substringAfter("://", "").substringAfter('/', "")
        return when {
            uri.contains("://boxd.it/") -> uri
            uri.contains("letterboxd.com/film/") && path.startsWith("film/") -> "$uri/"
            else -> null
        }
    }

    fun latest(first: LocalDate?, second: LocalDate?): LocalDate? = listOfNotNull(first, second).maxOrNull()
}
