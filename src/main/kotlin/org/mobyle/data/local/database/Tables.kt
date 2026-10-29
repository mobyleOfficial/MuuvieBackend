package org.mobyle.data.local.database

import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.CurrentTimestamp
import org.jetbrains.exposed.sql.kotlin.datetime.date
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object UsersTable : LongIdTable("users") {
    val externalId = varchar("external_id", 255).uniqueIndex()
    val username = varchar("username", 255).uniqueIndex()
    val email = varchar("email", 255).nullable()
    val avatarUrl = varchar("avatar_url", 500).nullable()
    val bio = varchar("bio", 500).nullable()
    val passwordHash = varchar("password_hash", 255).nullable()
    val createdAt = timestamp("created_at")
    // "Assistindo agora" (F2.6). References movies, declared there to avoid a forward reference.
    val watchingNowMovieId = long("watching_now_movie_id").nullable()
    // Public profile (B5). handle is the @ of the profile URL: ^[a-z0-9_.]{5,30}$, unique.
    val handle = varchar("handle", 30).nullable()
    val displayName = varchar("display_name", 50).nullable()
    val pronouns = varchar("pronouns", 30).nullable()
    val bannerUrl = varchar("banner_url", 500).nullable()
    val accentColor = varchar("accent_color", 7).nullable()
    val links = array<String>("links").default(emptyList())
    // Curated profiles come first in the discovery hub.
    val featured = bool("featured").default(false)
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
    val overviewLanguage = varchar("overview_language", 10).nullable() // ISO 639-1; null is Portuguese
    val overviewTranslated = bool("overview_translated").default(false) // machine translated (DeepL)
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
    val originalLanguage = varchar("original_language", 10).nullable() // ISO 639-1, from TMDB (B7)
    // TMDB link state of scraped movies (V19, filmow-movie-enrichment).
    val matchStatus = varchar("match_status", 20).default("pending")
    val matchMethod = varchar("match_method", 20).nullable()
    val enrichmentAttempts = integer("enrichment_attempts").default(0)
    val enrichmentRetryAt = timestamp("enrichment_retry_at").nullable()
}

/** Production countries of a movie (ISO 3166-1), read by the maratona challenges (B7). */
object MovieCountriesTable : Table("movie_countries") {
    val movieId = reference("movie_id", MoviesTable, onDelete = ReferenceOption.CASCADE)
    val country = varchar("country", 2)

    override val primaryKey = PrimaryKey(movieId, country)
}

/** TMDB keywords of a movie, read by the maratona challenges (B7). */
object MovieKeywordsTable : Table("movie_keywords") {
    val movieId = reference("movie_id", MoviesTable, onDelete = ReferenceOption.CASCADE)
    val keywordId = integer("keyword_id")
    val name = varchar("name", 255)

    override val primaryKey = PrimaryKey(movieId, keywordId)
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
    // watched and want_to_watch mirror the system shelves; none otherwise.
    val status = varchar("status", 50)
    // A Rating label (phase 13); the review and its spoiler flag belong to it.
    val rating = varchar("rating", 20).nullable()
    val review = text("review").nullable()
    val reviewHasSpoiler = bool("review_has_spoiler").default(false)
    val ratedAt = timestamp("rated_at").nullable()
    val rewatches = integer("rewatches").default(0)
    // Progress of the current cycle (rewatch), summarized from watch_logs.
    val currentCycle = integer("current_cycle").default(1)
    val progressMinutes = integer("progress_minutes").nullable()
    val progressPercent = short("progress_percent").nullable()
    val watchedAt = timestamp("watched_at").nullable()
    // Who owns the row: manual (Muuvie) or the import that created it (filmow, letterboxd).
    // Imports never overwrite a manual row.
    val importSource = varchar("source", 50)
    val importedAt = timestamp("imported_at").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    init {
        uniqueIndex("uq_user_movie", userId, movieId)
    }
}

object ShelvesTable : LongIdTable("shelves") {
    val userId = reference("user_id", UsersTable)
    val name = varchar("name", 255)
    val description = text("description").nullable()
    val color = varchar("color", 7).nullable()
    val visibility = varchar("visibility", 20).default("public") // public, mutual, secret
    // watched ("Já assisti") or watchlist ("Quero ver"); null for the user's own shelves.
    val systemType = varchar("system_type", 20).nullable()
    val position = integer("position").default(0) // order on the profile
    val pinned = bool("pinned").default(false)
    val sortMode = varchar("sort_mode", 30).default("manual")
    val importSource = varchar("import_source", 50).nullable()
    val externalId = varchar("external_id", 255).nullable()
    val coverUrl = varchar("cover_url", 500).nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp)
}

object ShelfItemsTable : LongIdTable("shelf_items") {
    val shelfId = reference("shelf_id", ShelvesTable)
    val movieId = reference("movie_id", MoviesTable)
    val position = integer("position")
    val addedAt = timestamp("added_at")

    init {
        uniqueIndex("uq_shelf_movie", shelfId, movieId)
    }
}

/** A shelf of someone else that a user saved (B14.4). */
object ShelfSavesTable : Table("shelf_saves") {
    val shelfId = reference("shelf_id", ShelvesTable, onDelete = ReferenceOption.CASCADE)
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE)
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)

    override val primaryKey = PrimaryKey(shelfId, userId)
}

/** The last time a logged-in user other than the owner opened a shelf (B14.4). */
object ShelfVisitsTable : Table("shelf_visits") {
    val shelfId = reference("shelf_id", ShelvesTable, onDelete = ReferenceOption.CASCADE)
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE)
    val visitedAt = timestamp("visited_at")

    override val primaryKey = PrimaryKey(shelfId, userId)
}

object WatchLogsTable : LongIdTable("watch_logs") {
    val userId = reference("user_id", UsersTable)
    val movieId = reference("movie_id", MoviesTable)
    val watchedOn = date("watched_on")
    val cycle = integer("cycle").default(1)
    val mode = varchar("mode", 10) // watched, minutes, percent
    val minutesWatched = integer("minutes_watched").nullable()
    val percent = short("percent").nullable()
    val completed = bool("completed").default(false)
    val venue = varchar("venue", 20).nullable()
    val providerName = varchar("provider_name", 255).nullable()
    val emotionIds = array<Short>("emotion_ids").default(emptyList())
    val gifId = varchar("gif_id", 100).nullable()
    val gifUrl = varchar("gif_url", 1000).nullable()
    val comment = text("comment").nullable()
    val hasSpoiler = bool("has_spoiler").default(false)
    val visibility = varchar("visibility", 20).default("public")
    val importSource = varchar("import_source", 50).nullable()
    val externalId = varchar("external_id", 255).nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
}

object CommentsTable : LongIdTable("comments") {
    val userId = reference("user_id", UsersTable)
    val targetType = varchar("target_type", 20) // watch_log, rating, maratona, cineclube
    val targetId = long("target_id")
    val parentId = optReference("parent_id", CommentsTable)
    val body = text("body")
    val hasSpoiler = bool("has_spoiler").default(false)
    val createdAt = timestamp("created_at")
    val deletedAt = timestamp("deleted_at").nullable()
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
    // Set once ArticleMovieLinker has looked for the movies the article mentions.
    val moviesLinkedAt = timestamp("movies_linked_at").nullable()
}

object ArticleMoviesTable : Table("article_movies") {
    val articleId = reference("article_id", ArticlesTable, onDelete = ReferenceOption.CASCADE)
    val movieId = reference("movie_id", MoviesTable, onDelete = ReferenceOption.CASCADE)
    val confidence = float("confidence")

    override val primaryKey = PrimaryKey(articleId, movieId)
}

object ProfileHighlightsTable : LongIdTable("profile_highlights") {
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE)
    val type = varchar("type", 20) // top4, shelf, locadora, soundtrack
    val position = integer("position")
    val enabled = bool("enabled").default(true)
    val payload = text("payload") // JSON as TEXT
    val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp)

    init {
        uniqueIndex("uq_profile_highlight_type", userId, type)
    }
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
    val userId = optReference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE)
    val tokenHash = varchar("token_hash", 64).uniqueIndex()
    val expiresAt = long("expires_at")
    val revokedAt = long("revoked_at").nullable()
    val createdAt = timestamp("created_at")
}

object LocadorasTable : LongIdTable("locadoras") {
    val userId = reference("user_id", UsersTable)
    val name = varchar("name", 255)
    val description = text("description").nullable()
    val visibility = varchar("visibility", 20).default("public") // public, mutual, secret
    // Hide the locadora's shelves from the profile's shelf tab and from the shelf filters.
    val hideShelvesInProfile = bool("hide_shelves_in_profile").default(false)
    val hideShelvesInFilters = bool("hide_shelves_in_filters").default(false)
    val position = integer("position").default(0) // order on the profile
    val pinned = bool("pinned").default(false)
    val coverUrl = varchar("cover_url", 500).nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp)
}

object LocadoraShelvesTable : LongIdTable("locadora_shelves") {
    val locadoraId = reference("locadora_id", LocadorasTable)
    val shelfId = reference("shelf_id", ShelvesTable)
    val position = integer("position")
    val addedAt = timestamp("added_at")

    init {
        uniqueIndex("uq_locadora_shelf", locadoraId, shelfId)
    }
}

object ImportJobsTable : LongIdTable("import_jobs") {
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE)
    val importSource = varchar("source", 20) // filmow, letterboxd
    val status = varchar("status", 20) // pending, reading, preview, importing, done, failed, cancelled
    val inputRef = varchar("input_ref", 255) // username or file key
    // JSON as TEXT: the bundle read from the source is dropped when the job ends.
    val bundle = text("bundle").nullable()
    val preview = text("preview").nullable()
    val options = text("options").nullable()
    val counts = text("counts").nullable()
    val processed = integer("processed").default(0)
    val total = integer("total").default(0)
    val error = text("error").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    val finishedAt = timestamp("finished_at").nullable()
}

object ImportUnmatchedTable : LongIdTable("import_unmatched") {
    val jobId = reference("job_id", ImportJobsTable, onDelete = ReferenceOption.CASCADE)
    val externalId = varchar("external_id", 255)
    val raw = text("raw") // the ImportedMovie as JSON
    val candidates = text("candidates") // up to 3 MovieCandidate as JSON
    val resolution = varchar("resolution", 20).default("pending") // pending, matched, skipped
    val movieId = optReference("movie_id", MoviesTable, onDelete = ReferenceOption.SET_NULL)
    val createdAt = timestamp("created_at")
}

// ── Social (B6) ──────────────────────────────────────────────────────────

object ActivitiesTable : LongIdTable("activities") {
    val actorId = reference("actor_id", UsersTable, onDelete = ReferenceOption.CASCADE)
    val type = varchar("type", 30)
    val visibility = varchar("visibility", 20).default("public") // public, mutual, secret
    val subjectType = varchar("subject_type", 20) // movie, shelf, locadora, import, maratona, cineclube, bichinho
    val subjectId = long("subject_id")
    val movieId = optReference("movie_id", MoviesTable, onDelete = ReferenceOption.CASCADE)
    val payload = text("payload") // JSON as TEXT
    val hidden = bool("hidden").default(false)
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
    val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp)
}

object ActivityLovesTable : Table("activity_loves") {
    val activityId = reference("activity_id", ActivitiesTable, onDelete = ReferenceOption.CASCADE)
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE)
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)

    override val primaryKey = PrimaryKey(activityId, userId)
}

object ConversationsTable : LongIdTable("conversations") {
    // The pair is stored once, with the smaller users.id first.
    val userA = reference("user_a", UsersTable, onDelete = ReferenceOption.CASCADE)
    val userB = reference("user_b", UsersTable, onDelete = ReferenceOption.CASCADE)
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
    val lastMessageAt = timestamp("last_message_at").defaultExpression(CurrentTimestamp)
}

object MessagesTable : LongIdTable("messages") {
    val conversationId = reference("conversation_id", ConversationsTable, onDelete = ReferenceOption.CASCADE)
    // Null for system messages; their body is a code (SystemMessage).
    val senderId = optReference("sender_id", UsersTable, onDelete = ReferenceOption.CASCADE)
    val body = varchar("body", 2000)
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
}

object ConversationReadsTable : Table("conversation_reads") {
    val conversationId = reference("conversation_id", ConversationsTable, onDelete = ReferenceOption.CASCADE)
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE)
    val lastReadMessageId = long("last_read_message_id").default(0)

    override val primaryKey = PrimaryKey(conversationId, userId)
}

object DeviceTokensTable : LongIdTable("device_tokens") {
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE)
    val platform = varchar("platform", 10) // android, ios, web
    val token = varchar("token", 500).uniqueIndex()
    val locale = varchar("locale", 10).nullable()
    val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp)
}

object NotificationPrefsTable : Table("notification_prefs") {
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE)
    val prefs = text("prefs") // JSON as TEXT
    val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp)

    override val primaryKey = PrimaryKey(userId)
}

// ── Maratonas (B7) ───────────────────────────────────────────────────────

object MaratonasTable : LongIdTable("maratonas") {
    val slug = varchar("slug", 50).uniqueIndex("uq_maratonas_slug")
    val ownerId = reference("owner_id", UsersTable, onDelete = ReferenceOption.CASCADE)
    val title = varchar("title", 100)
    val description = varchar("description", 2000).nullable()
    val logoUrl = varchar("logo_url", 500).nullable()
    val accentColor = varchar("accent_color", 7).nullable()
    val startsOn = date("starts_on")
    val endsOn = date("ends_on")
    val status = varchar("status", 20).default("upcoming") // upcoming, active, finished, cancelled
    val passwordHash = varchar("password_hash", 255).nullable()
    val socialLink = varchar("social_link", 500).nullable()
    val trusted = bool("trusted").default(false)
    val templateId = varchar("template_id", 50).nullable()
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
    val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp)
}

object MaratonaOwnersTable : Table("maratona_owners") {
    val maratonaId = reference("maratona_id", MaratonasTable, onDelete = ReferenceOption.CASCADE)
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE)

    override val primaryKey = PrimaryKey(maratonaId, userId)
}

object MaratonaCategoriesTable : LongIdTable("maratona_categories") {
    val maratonaId = reference("maratona_id", MaratonasTable, onDelete = ReferenceOption.CASCADE)
    val name = varchar("name", 80)
    val position = integer("position").default(0)
    val teamOnly = bool("team_only").default(false)
}

object MaratonaChallengesTable : LongIdTable("maratona_challenges") {
    val categoryId = reference("category_id", MaratonaCategoriesTable, onDelete = ReferenceOption.CASCADE)
    val description = varchar("description", 300)
    val type = varchar("type", 20).default("free") // criteria, free
    val criteria = text("criteria").nullable() // JSON as TEXT
    val position = integer("position").default(0)
    val daily = bool("daily").default(false)
}

object MaratonaLevelsTable : LongIdTable("maratona_levels") {
    val maratonaId = reference("maratona_id", MaratonasTable, onDelete = ReferenceOption.CASCADE)
    val name = varchar("name", 50)
    val minChallenges = integer("min_challenges")
}

object MaratonaTeamsTable : LongIdTable("maratona_teams") {
    val maratonaId = reference("maratona_id", MaratonasTable, onDelete = ReferenceOption.CASCADE)
    val name = varchar("name", 50)
    val color = varchar("color", 7).nullable()
}

object MaratonaPrizesTable : LongIdTable("maratona_prizes") {
    val maratonaId = reference("maratona_id", MaratonasTable, onDelete = ReferenceOption.CASCADE)
    val title = varchar("title", 100)
    val description = varchar("description", 500).nullable()
}

object MaratonaParticipationsTable : LongIdTable("maratona_participations") {
    val maratonaId = reference("maratona_id", MaratonasTable, onDelete = ReferenceOption.CASCADE)
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE)
    val levelId = optReference("level_id", MaratonaLevelsTable, onDelete = ReferenceOption.SET_NULL)
    val teamId = optReference("team_id", MaratonaTeamsTable, onDelete = ReferenceOption.SET_NULL)
    val queueShelfId = optReference("queue_shelf_id", ShelvesTable, onDelete = ReferenceOption.SET_NULL)
    val joinedAt = timestamp("joined_at").defaultExpression(CurrentTimestamp)

    init {
        uniqueIndex("uq_maratona_participations", maratonaId, userId)
    }
}

object ChallengeLinksTable : LongIdTable("challenge_links") {
    val participationId = reference("participation_id", MaratonaParticipationsTable, onDelete = ReferenceOption.CASCADE)
    val challengeId = reference("challenge_id", MaratonaChallengesTable, onDelete = ReferenceOption.CASCADE)
    val movieId = reference("movie_id", MoviesTable, onDelete = ReferenceOption.CASCADE)
    val watchLogId = optReference("watch_log_id", WatchLogsTable, onDelete = ReferenceOption.SET_NULL)
    val linkSource = varchar("source", 10) // manual, auto
    val completedAt = timestamp("completed_at").defaultExpression(CurrentTimestamp)

    init {
        uniqueIndex("uq_challenge_links", participationId, challengeId, movieId)
    }
}

object CineClubesTable : LongIdTable("cineclubes") {
    val slug = varchar("slug", 50).uniqueIndex("uq_cineclubes_slug")
    val ownerId = reference("owner_id", UsersTable, onDelete = ReferenceOption.CASCADE)
    val title = varchar("title", 100)
    val description = varchar("description", 2000).nullable()
    val accentColor = varchar("accent_color", 7).nullable()
    val passwordHash = varchar("password_hash", 255).nullable()
    val debateLink = varchar("debate_link", 500).nullable()
    val socialLink = varchar("social_link", 500).nullable()
    val status = varchar("status", 20).default("upcoming") // upcoming, active, finished, cancelled
    val isCycle = bool("is_cycle").default(false)
    // Span of the sessions: first start, last end or debate.
    val startsOn = date("starts_on")
    val endsOn = date("ends_on")
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
    val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp)
}

object CineClubeSessionsTable : LongIdTable("cineclube_sessions") {
    val cineclubeId = reference("cineclube_id", CineClubesTable, onDelete = ReferenceOption.CASCADE)
    val movieId = reference("movie_id", MoviesTable)
    val position = integer("position").default(0)
    val startsOn = date("starts_on")
    val endsOn = date("ends_on")
    val debateOn = date("debate_on")
    val openedAt = timestamp("opened_at").nullable()
    val debateNotifiedAt = timestamp("debate_notified_at").nullable()
}

object CineClubeParticipationsTable : LongIdTable("cineclube_participations") {
    val cineclubeId = reference("cineclube_id", CineClubesTable, onDelete = ReferenceOption.CASCADE)
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE)
    val joinedAt = timestamp("joined_at").defaultExpression(CurrentTimestamp)

    init {
        uniqueIndex("uq_cineclube_participations", cineclubeId, userId)
    }
}

object YearlyGoalsTable : LongIdTable("yearly_goals") {
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE)
    val year = integer("year")
    val targetMovies = integer("target_movies")
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
    val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp)

    init {
        uniqueIndex("uq_yearly_goals", userId, year)
    }
}

object UserPetsTable : LongIdTable("user_pets") {
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE)
    val petSlug = varchar("pet_slug", 50)
    val xp = integer("xp").default(0)
    val starter = bool("starter").default(false)
    val pinned = bool("pinned").default(false)
    val unlockedAt = timestamp("unlocked_at").defaultExpression(CurrentTimestamp)

    init {
        uniqueIndex("uq_user_pets", userId, petSlug)
    }
}

object UserMissionsTable : LongIdTable("user_missions") {
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE)
    val weekStart = date("week_start")
    val missionSlug = varchar("mission_slug", 50)
    val xp = integer("xp")
    val completedAt = timestamp("completed_at").defaultExpression(CurrentTimestamp)

    init {
        uniqueIndex("uq_user_missions", userId, weekStart, missionSlug)
    }
}

object DiaryDaysTable : LongIdTable("diary_days") {
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE)
    val day = date("day")
    val mood = varchar("mood", 10).nullable() // great, good, okay, bad, awful
    val note = text("note").nullable()
    val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp)

    init {
        uniqueIndex("uq_diary_days", userId, day)
    }
}

object DiaryTasksTable : LongIdTable("diary_tasks") {
    val dayId = reference("day_id", DiaryDaysTable, onDelete = ReferenceOption.CASCADE)
    val category = varchar("category", 10) // movie, short, series, study, other
    val description = varchar("description", 200)
    val done = bool("done").default(false)
    val position = integer("position").default(0)
    val movieId = optReference("movie_id", MoviesTable, onDelete = ReferenceOption.SET_NULL)
}
