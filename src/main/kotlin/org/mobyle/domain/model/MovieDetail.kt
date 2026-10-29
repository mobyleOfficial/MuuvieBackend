package org.mobyle.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import org.mobyle.domain.model.vocabulary.MovieMatchStatus

@Serializable
data class MovieDetail(
    val id: Long = 0L,
    val tmdbId: Int? = null,
    val title: String,
    val localTitle: String? = null,
    val originalTitle: String? = null,
    val overview: String,
    // ISO 639-1 of the overview when it is not in Portuguese (TMDB had no pt-BR one); null otherwise.
    val overviewLanguage: String? = null,
    // True when the overview was machine translated into Portuguese (DeepL).
    val overviewTranslated: Boolean = false,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    // Kept for the catalog cache only; the TMDB score is not shown (D3, phase 13).
    @Transient val voteAverage: Double = 0.0,
    val releaseDate: String? = null,
    val tagline: String? = null,
    val runtime: Int? = null,
    val genres: List<String> = emptyList(),
    val director: Person? = null,
    val writers: List<Person> = emptyList(),
    val cast: List<CastMember> = emptyList(),
    val trailerKey: String? = null,
    val watchProviders: List<WatchProvider> = emptyList(),
    val similarMovies: List<Movie> = emptyList(),
    // The viewer's state for the movie (F2.7); only on authenticated requests.
    val me: MovieUserState? = null,
    // Whether the movie is linked to TMDB; unmatched movies only have the source's data.
    val matchStatus: MovieMatchStatus = MovieMatchStatus.MATCHED
)
