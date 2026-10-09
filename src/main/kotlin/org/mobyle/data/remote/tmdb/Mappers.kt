package org.mobyle.data.remote.tmdb

import org.mobyle.data.remote.tmdb.model.*
import org.mobyle.domain.model.*
import org.mobyle.model.MovieListing
import org.mobyle.model.MovieReviewListing
import org.slf4j.LoggerFactory

private val mapperLog = LoggerFactory.getLogger("TmdbMapper")

fun TmdbMovieResponse.toDomain(): Movie {
    return Movie(
        tmdbId = id,
        title = title ?: "",
        originalTitle = originalTitle,
        overview = overview ?: "",
        posterPath = posterPath,
        backdropPath = backdropPath,
        voteAverage = voteAverage ?: 0.0,
        releaseDate = releaseDate
    )
}

fun TmdbMovieListResponse.toDomain(): MovieListing {
    return MovieListing(
        totalPages = totalPages,
        totalResults = totalResults,
        movies = results.map { it.toDomain() }
    )
}

fun TmdbMovieDetailResponse.toDomain(): MovieDetail {
    val director = runCatching {
        credits?.crew?.firstOrNull { it.job == "Director" }?.let { crew ->
            val tmdbId = crew.id ?: return@let null
            Person(id = 0L, tmdbPersonId = tmdbId, name = crew.name ?: "", profilePath = crew.profilePath)
        }
    }.getOrElse { mapperLog.warn("director mapping failed: ${it.message}"); null }

    val writers = runCatching {
        credits?.crew
            ?.filter { it.job in listOf("Screenplay", "Writer", "Story") }
            ?.distinctBy { it.id }
            ?.mapNotNull { crew ->
                val tmdbId = crew.id ?: return@mapNotNull null
                Person(id = 0L, tmdbPersonId = tmdbId, name = crew.name ?: "", profilePath = crew.profilePath)
            } ?: emptyList()
    }.getOrElse { mapperLog.warn("writers mapping failed: ${it.message}"); emptyList() }

    val cast = runCatching {
        credits?.cast?.take(10)?.mapNotNull { member ->
            val tmdbId = member.id ?: return@mapNotNull null
            CastMember(
                person = Person(id = 0L, tmdbPersonId = tmdbId, name = member.name ?: "", profilePath = member.profilePath),
                character = member.character,
                order = member.order
            )
        } ?: emptyList()
    }.getOrElse { mapperLog.warn("cast mapping failed: ${it.message}"); emptyList() }

    val trailerKey = runCatching {
        videos?.results
            ?.filter { it.site == "YouTube" && it.type == "Trailer" }
            ?.sortedByDescending { it.official }
            ?.firstOrNull()?.key
    }.getOrElse { mapperLog.warn("trailer mapping failed: ${it.message}"); null }

    val providers = watchProviders?.results?.values
        ?.flatMap { it.flatrate }
        ?.distinctBy { it.providerName }
        ?.map { WatchProvider(name = it.providerName ?: "", logoPath = it.logoPath) }
        ?: emptyList()

    return MovieDetail(
        tmdbId = id,
        title = title ?: "",
        originalTitle = originalTitle,
        overview = overview ?: "",
        posterPath = posterPath,
        backdropPath = backdropPath,
        voteAverage = voteAverage ?: 0.0,
        releaseDate = releaseDate,
        tagline = tagline,
        runtime = runtime,
        genres = genres.mapNotNull { it.name },
        director = director,
        writers = writers,
        cast = cast,
        trailerKey = trailerKey,
        watchProviders = providers,
        similarMovies = similar?.results?.take(10)?.map { it.toDomain() } ?: emptyList(),
        popularReviews = reviews?.results?.take(5)?.map { it.toDomain() } ?: emptyList(),
        reviewCount = reviews?.totalResults ?: 0
    )
}

fun TmdbReview.toDomain(): MovieReview {
    return MovieReview(
        id = id ?: "",
        title = "",
        date = createdAt,
        rating = authorDetails?.rating ?: 0.0,
        author = author,
        content = content
    )
}

fun TmdbReviewListResponse.toDomain(): MovieReviewListing {
    return MovieReviewListing(
        totalPages = totalPages,
        totalResults = totalResults,
        reviews = results.map { it.toDomain() }
    )
}

fun TmdbGenre.toDomain(): Genre {
    return Genre(id = id, name = name ?: "")
}

fun TmdbCountry.toDomain(): Country {
    return Country(iso = iso, englishName = englishName ?: "")
}

fun TmdbLanguage.toDomain(): Language {
    return Language(iso = iso, englishName = englishName ?: "")
}
