package org.mobyle.domain.model

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import org.mobyle.domain.model.vocabulary.ImportSource
import org.mobyle.domain.model.vocabulary.ImportStatus
import org.mobyle.domain.model.vocabulary.MovieMatchMethod
import org.mobyle.domain.model.vocabulary.Visibility
import org.mobyle.domain.policy.MovieMatchPolicy

/** A movie as the source knows it. [externalId] is the id on the source (e.g. the Filmow id). */
@Serializable
data class ImportedMovie(
    val externalId: String,
    val title: String,
    val originalTitle: String? = null,
    // The title in Portuguese, as the source shows it.
    val localTitle: String? = null,
    val year: Int? = null,
    val posterUrl: String? = null,
    val overview: String? = null,
    val releaseDate: String? = null,
    val runtime: Int? = null,
    val director: String? = null,
    val genres: List<String> = emptyList(),
    val imdbUrl: String? = null,
    // 0.5-5 stars, as both sources rate.
    val stars: Double? = null,
    // The latest watch; the date of the movie on "Já assisti".
    val watchedOn: LocalDate? = null,
    // Every session the source has (a Letterboxd diary logs rewatches); each becomes a watch log.
    val watchDates: List<LocalDate> = emptyList(),
    // The user's review on the source. With a rating it is the rating's review (one per movie);
    // without one it is the comment of the session of [reviewOn], or of the latest session.
    val review: String? = null,
    val reviewOn: LocalDate? = null,
    // Set by the matcher before writing, when TMDB matched with confidence.
    val tmdbId: Int? = null,
    // MovieMatchMethod wire value of that match.
    val matchMethod: String? = null
) {
    /** The dates that become watch logs, oldest first. */
    val sessionDates: List<LocalDate> get() = (watchDates + listOfNotNull(watchedOn)).distinct().sorted()

    companion object {
        /** Prefix of [externalId]s made from the title and year, which are not ids on the source. */
        const val TITLE_KEY_PREFIX = "title:"

        /** The id of a movie the source gives no id for; the same movie gets the same key on reimports. */
        fun titleKey(title: String, year: Int?): String =
            "$TITLE_KEY_PREFIX${MovieMatchPolicy.normalize(title)}:${year ?: ""}"
    }
}

@Serializable
data class ImportedList(
    val externalId: String,
    val title: String,
    val description: String? = null,
    val coverUrl: String? = null,
    val movies: List<ImportedMovie> = emptyList()
)

/**
 * What a source reader produces, neutral to the source. Watched movies with a date also
 * become watch logs; favorites are watched movies with the heart on.
 */
@Serializable
data class ImportBundle(
    val watched: List<ImportedMovie> = emptyList(),
    val watchlist: List<ImportedMovie> = emptyList(),
    val favorites: List<ImportedMovie> = emptyList(),
    val lists: List<ImportedList> = emptyList()
) {
    /** Every movie of the bundle once, by external id. */
    val distinctMovies: List<ImportedMovie>
        get() = (watched + watchlist + favorites + lists.flatMap { it.movies }).distinctBy { it.externalId }

    fun preview(): ImportPreview = ImportPreview(
        watched = watched.size,
        watchlist = watchlist.size,
        favorites = favorites.size,
        logs = watched.sumOf { it.sessionDates.size },
        reviews = watched.count { it.review != null },
        lists = lists.map { ImportPreviewList(it.externalId, it.title, it.movies.size, it.coverUrl) }
    )

    /** Keeps only the chosen lists (by external id); null keeps all. */
    fun withLists(externalIds: Set<String>?): ImportBundle =
        if (externalIds == null) this else copy(lists = lists.filter { it.externalId in externalIds })
}

/** F3.5: what the import is about to write. */
@Serializable
data class ImportPreview(
    val watched: Int,
    val watchlist: Int,
    val favorites: Int,
    val logs: Int,
    val lists: List<ImportPreviewList>,
    // Reviews that become the review of the movie's rating.
    val reviews: Int = 0
)

@Serializable
data class ImportPreviewList(val externalId: String, val title: String, val movieCount: Int, val coverUrl: String? = null)

/** What the user chose on the preview. */
@Serializable
data class ImportOptions(
    // null imports every list.
    val listsToImport: List<String>? = null,
    // Visibility of the shelves and logs the import creates.
    val defaultVisibility: Visibility = Visibility.DEFAULT
)

/** Final report (F3.6). The same import run twice reports the same counts. */
@Serializable
data class ImportCounts(
    val watched: Int = 0,
    val watchlist: Int = 0,
    val favorites: Int = 0,
    val lists: Int = 0,
    val logs: Int = 0,
    val unmatched: Int = 0,
    // Movies already owned by the user in the Muuvie, left as they were.
    val keptFromMuuvie: Int = 0,
    val reviews: Int = 0
)

@Serializable
data class ImportJob(
    val id: Long,
    val source: ImportSource,
    val status: ImportStatus,
    val inputRef: String,
    val processed: Int,
    val total: Int,
    val preview: ImportPreview?,
    val options: ImportOptions?,
    val counts: ImportCounts?,
    val error: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val finishedAt: Instant?,
    // External id of the owner; not part of the response.
    @Transient val userId: String = ""
)

/** A TMDB movie offered to the user for an unmatched import item. */
@Serializable
data class MovieCandidate(
    val tmdbId: Int,
    val title: String,
    val originalTitle: String? = null,
    val year: Int? = null,
    val posterPath: String? = null,
    val score: Double
)

/** What TMDB says about a candidate, fetched only when the title and the year are not enough. */
data class MovieCandidateFacts(
    // Localized, English, original and alternative titles.
    val titles: List<String>,
    val year: Int?,
    val directors: List<String>
)

/** Where the resolution of a movie without TMDB id ended (filmow-movie-enrichment). */
sealed interface MovieResolution {
    data class Matched(val tmdbId: Int, val method: MovieMatchMethod) : MovieResolution
    /** No candidate matched with confidence; [candidates] go to the confirmation queue. */
    data class Unmatched(val candidates: List<MovieCandidate>) : MovieResolution
}

/** F3.4: an imported movie waiting for the user to confirm its TMDB match. */
@Serializable
data class UnmatchedItem(
    val id: Long,
    val jobId: Long,
    val movie: ImportedMovie,
    val candidates: List<MovieCandidate>,
    val resolution: String,
    val movieId: Long?
)
