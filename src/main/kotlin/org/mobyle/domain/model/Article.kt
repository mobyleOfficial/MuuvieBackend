package org.mobyle.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class Article(
    val id: Long,
    val title: String,
    val summary: String,
    val content: String,
    val imageUrl: String?,
    val sourceUrl: String,
    val source: String,
    val publishedAt: String,
    // Movies the article cites (F5.8). Only filled on the v1 API.
    val movies: List<ArticleMovie> = emptyList()
)

/** A movie cited by an article. [me] is the viewer's state, only when logged in. */
@Serializable
data class ArticleMovie(
    val movieId: Long,
    val tmdbId: Int?,
    val title: String,
    val posterPath: String?,
    val year: Int?,
    val me: ArticleMovieState? = null
)

/** Whether the viewer already has the movie on Quero ver or Já assisti, for the 1-tap action. */
@Serializable
data class ArticleMovieState(val inQueroVer: Boolean, val watched: Boolean)
