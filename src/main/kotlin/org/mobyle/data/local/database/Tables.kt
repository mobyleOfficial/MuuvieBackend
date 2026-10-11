package org.mobyle.data.local.database

import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object UsersTable : LongIdTable("users") {
    val externalId = varchar("external_id", 255).uniqueIndex()
    val username = varchar("username", 255).uniqueIndex()
    val email = varchar("email", 255).nullable()
    val avatarUrl = varchar("avatar_url", 500).nullable()
    val bio = varchar("bio", 500).nullable()
    val passwordHash = varchar("password_hash", 255).nullable()
    val createdAt = timestamp("created_at")
}

object UserFollowsTable : LongIdTable("user_follows") {
    val followerId = reference("follower_id", UsersTable)
    val followedId = reference("followed_id", UsersTable)
    val createdAt = timestamp("created_at")

    init {
        uniqueIndex("uq_follower_followed", followerId, followedId)
    }
}

object MoviesTable : LongIdTable("movies") {
    val tmdbId = integer("tmdb_id").nullable().uniqueIndex()
    val title = varchar("title", 500)
    val localTitle = varchar("local_title", 500).nullable()
    val originalTitle = varchar("original_title", 500).nullable()
    val year = integer("year").nullable()
    val posterPath = varchar("poster_path", 500).nullable()
    val voteAverage = float("vote_average").nullable()
    val filmowId = varchar("filmow_id", 50).nullable().index("idx_movies_filmow_id")
    val overview = text("overview").nullable()
    val backdropPath = varchar("backdrop_path", 500).nullable()
    val releaseDate = varchar("release_date", 20).nullable()
    val runtime = integer("runtime").nullable()
    val tagline = varchar("tagline", 1000).nullable()
    val letterboxdId = varchar("letterboxd_id", 100).nullable().index("idx_movies_letterboxd_id")
    val enrichedAt = timestamp("enriched_at").nullable()
    val needsEnrichment = bool("needs_enrichment").default(true)
    val imdbUrl = varchar("imdb_url", 500).nullable()
    val director = varchar("director", 255).nullable()
    val trailerKey = varchar("trailer_key", 20).nullable()
    val filmowGenres = text("filmow_genres").nullable() // comma-separated genre names from Filmow
}

object GenresTable : LongIdTable("genres") {
    val tmdbId = integer("tmdb_id").uniqueIndex()
    val name = varchar("name", 100)
}

object MovieGenresTable : LongIdTable("movie_genres") {
    val movieId = reference("movie_id", MoviesTable)
    val genreId = reference("genre_id", GenresTable)

    init {
        uniqueIndex("uq_movie_genre", movieId, genreId)
    }
}

object PeopleTable : LongIdTable("people") {
    val tmdbId = integer("tmdb_id").uniqueIndex()
    val name = varchar("name", 255)
    val profilePath = varchar("profile_path", 500).nullable()
    val fetchedAt = timestamp("fetched_at").nullable()
}

object MovieCastTable : LongIdTable("movie_cast") {
    val movieId = reference("movie_id", MoviesTable)
    val personId = reference("person_id", PeopleTable)
    val role = varchar("role", 50)
    val character = varchar("character", 255).nullable()
    val position = integer("position").default(0)
    val fetchedAt = timestamp("fetched_at").nullable()

    init {
        uniqueIndex("uq_movie_person_role", movieId, personId, role)
    }
}

object MovieSimilarsTable : LongIdTable("movie_similars") {
    val movieId = reference("movie_id", MoviesTable)
    val similarTmdbId = integer("similar_tmdb_id")
    val title = varchar("title", 500)
    val posterPath = varchar("poster_path", 500).nullable()
    val voteAverage = float("vote_average").nullable()
    val releaseDate = varchar("release_date", 20).nullable()
    val fetchedAt = timestamp("fetched_at")

    init {
        uniqueIndex("uq_movie_similar", movieId, similarTmdbId)
    }
}

object MovieWatchProvidersTable : LongIdTable("movie_watch_providers") {
    val movieId = reference("movie_id", MoviesTable)
    val providerName = varchar("provider_name", 255)
    val logoPath = varchar("logo_path", 500).nullable()
    val fetchedAt = timestamp("fetched_at")

    init {
        uniqueIndex("uq_movie_provider", movieId, providerName)
    }
}

object UserMoviesTable : LongIdTable("user_movies") {
    val userId = reference("user_id", UsersTable)
    val movieId = reference("movie_id", MoviesTable)
    val status = varchar("status", 50) // watched, want_to_watch, dropped
    val rating = float("rating").nullable()
    val review = text("review").nullable()
    val rewatches = integer("rewatches").default(0)
    val watchedAt = timestamp("watched_at").nullable()
    val isFavorite = bool("is_favorite").default(false)
    val importSource = varchar("source", 50) // filmow, letterboxd, manual
    val importedAt = timestamp("imported_at").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    init {
        uniqueIndex("uq_user_movie_source", userId, movieId, importSource)
    }
}

object UserListsTable : LongIdTable("user_lists") {
    val userId = reference("user_id", UsersTable)
    val name = varchar("name", 255)
    val description = text("description").nullable()
    val isPublic = bool("is_public").default(true)
    val color = varchar("color", 7).nullable()
    val visibility = varchar("visibility", 20).default("public")
    val createdAt = timestamp("created_at")
}

object UserListItemsTable : LongIdTable("user_list_items") {
    val listId = reference("list_id", UserListsTable)
    val movieId = reference("movie_id", MoviesTable)
    val position = integer("position")
    val addedAt = timestamp("added_at")

    init {
        uniqueIndex("uq_list_movie", listId, movieId)
    }
}

object TagsTable : LongIdTable("tags") {
    val userId = reference("user_id", UsersTable)
    val name = varchar("name", 255)

    init {
        uniqueIndex("uq_user_tag", userId, name)
    }
}

object ArticlesTable : LongIdTable("articles") {
    val sourceUrl = varchar("source_url", 1000).uniqueIndex()
    val title = varchar("title", 500)
    val summary = text("summary")
    val content = text("content")
    val imageUrl = varchar("image_url", 1000).nullable()
    val sourceName = varchar("source", 100)
    val publishedAt = timestamp("published_at")
    val scrapedAt = timestamp("scraped_at")
}

object MovieTagsTable : LongIdTable("movie_tags") {
    val tagId = reference("tag_id", TagsTable)
    val userMovieId = reference("user_movie_id", UserMoviesTable)

    init {
        uniqueIndex("uq_tag_user_movie", tagId, userMovieId)
    }
}

object TokenBlocklistTable : LongIdTable("token_blocklist") {
    val token = varchar("token", 1000).uniqueIndex()
    val expiresAt = long("expires_at")
}

object RefreshTokensTable : LongIdTable("refresh_tokens") {
    val userExternalId = varchar("user_external_id", 255).index()
    val tokenHash = varchar("token_hash", 64).uniqueIndex()
    val expiresAt = long("expires_at")
    val revokedAt = long("revoked_at").nullable()
    val createdAt = timestamp("created_at")
}

object MovieLikesTable : LongIdTable("movie_likes") {
    val userExternalId = varchar("user_external_id", 255).index()
    val movieId = long("movie_id").index()
    val createdAt = timestamp("created_at")

    init {
        uniqueIndex("uq_movie_like", userExternalId, movieId)
    }
}

object ReviewLikesTable : LongIdTable("review_likes") {
    val userExternalId = varchar("user_external_id", 255).index()
    val reviewId = varchar("review_id", 255).index()
    val createdAt = timestamp("created_at")

    init {
        uniqueIndex("uq_review_like", userExternalId, reviewId)
    }
}
