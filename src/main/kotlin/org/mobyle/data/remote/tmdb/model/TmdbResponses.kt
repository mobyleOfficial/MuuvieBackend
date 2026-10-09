package org.mobyle.data.remote.tmdb.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TmdbMovieResponse(
    val id: Int,
    val title: String? = null,
    @SerialName("original_title") val originalTitle: String? = null,
    val overview: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    @SerialName("release_date") val releaseDate: String? = null
)

@Serializable
data class TmdbMovieListResponse(
    val page: Int = 1,
    @SerialName("total_pages") val totalPages: Int = 1,
    @SerialName("total_results") val totalResults: Int = 0,
    val results: List<TmdbMovieResponse> = emptyList()
)

@Serializable
data class TmdbMovieDetailResponse(
    val id: Int,
    val title: String? = null,
    @SerialName("original_title") val originalTitle: String? = null,
    val overview: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    val tagline: String? = null,
    val runtime: Int? = null,
    val genres: List<TmdbGenre> = emptyList(),
    val credits: TmdbCredits? = null,
    val videos: TmdbVideosResponse? = null,
    @SerialName("watch/providers") val watchProviders: TmdbWatchProvidersWrapper? = null,
    val similar: TmdbMovieListResponse? = null,
    val reviews: TmdbReviewListResponse? = null
)

@Serializable
data class TmdbCredits(
    val crew: List<TmdbCrewMember> = emptyList(),
    val cast: List<TmdbCastMember> = emptyList()
)

@Serializable
data class TmdbCrewMember(
    val id: Int? = null,
    val name: String? = null,
    val job: String? = null,
    @SerialName("profile_path") val profilePath: String? = null
)

@Serializable
data class TmdbCastMember(
    val id: Int? = null,
    val name: String? = null,
    val character: String? = null,
    val order: Int? = null,
    @SerialName("profile_path") val profilePath: String? = null
)

@Serializable
data class TmdbWatchProvidersWrapper(
    val results: Map<String, TmdbWatchProviderRegion> = emptyMap()
)

@Serializable
data class TmdbWatchProviderRegion(
    val flatrate: List<TmdbWatchProviderEntry> = emptyList()
)

@Serializable
data class TmdbWatchProviderEntry(
    @SerialName("provider_name") val providerName: String? = null,
    @SerialName("logo_path") val logoPath: String? = null
)

@Serializable
data class TmdbVideosResponse(
    val results: List<TmdbVideo> = emptyList()
)

@Serializable
data class TmdbVideo(
    val key: String? = null,
    val site: String? = null,
    val type: String? = null,
    val official: Boolean = false
)

@Serializable
data class TmdbReviewListResponse(
    val page: Int = 1,
    @SerialName("total_pages") val totalPages: Int = 1,
    @SerialName("total_results") val totalResults: Int = 0,
    val results: List<TmdbReview> = emptyList()
)

@Serializable
data class TmdbReview(
    val id: String? = null,
    val author: String? = null,
    val content: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("author_details") val authorDetails: TmdbAuthorDetails? = null
)

@Serializable
data class TmdbAuthorDetails(
    val rating: Double? = null
)

@Serializable
data class TmdbGenre(
    val id: Int,
    val name: String? = null
)

@Serializable
data class TmdbGenreListResponse(
    val genres: List<TmdbGenre> = emptyList()
)

@Serializable
data class TmdbCountry(
    @SerialName("iso_3166_1") val iso: String,
    @SerialName("english_name") val englishName: String? = null
)

@Serializable
data class TmdbLanguage(
    @SerialName("iso_639_1") val iso: String,
    @SerialName("english_name") val englishName: String? = null
)

@Serializable
data class TmdbFindResponse(
    @SerialName("movie_results") val movieResults: List<TmdbMovieResponse> = emptyList()
)
