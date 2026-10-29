package org.mobyle.data.local.movies

import kotlinx.datetime.Clock
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.mobyle.data.local.database.*
import org.mobyle.data.remote.tmdb.model.TmdbCredits
import org.mobyle.domain.model.*
import org.mobyle.domain.model.vocabulary.MovieMatchMethod
import org.mobyle.domain.model.vocabulary.MovieMatchStatus
import kotlin.time.Duration.Companion.minutes
import org.slf4j.LoggerFactory

private val catalogLog = LoggerFactory.getLogger(MovieCatalogDataSourceImpl::class.java)

/** How good an overview is: Portuguese (TMDB, Filmow), then machine translated, then another language. */
private fun overviewRank(overview: String?, language: String?, translated: Boolean): Int = when {
    overview.isNullOrBlank() -> 0
    language != null -> 1
    translated -> 2
    else -> 3
}

class MovieCatalogDataSourceImpl : MovieCatalogDataSource {

    override fun findById(id: Long): Movie? {
        return transaction {
            MoviesTable.selectAll()
                .where { MoviesTable.id eq id }
                .firstOrNull()
                ?.let { rowToMovie(it) }
        }
    }

    override fun findByTmdbId(tmdbId: Int): Movie? {
        return transaction {
            MoviesTable.selectAll()
                .where { MoviesTable.tmdbId eq tmdbId }
                .firstOrNull()
                ?.let { rowToMovie(it) }
        }
    }

    override fun findByFilmowId(filmowId: String): Movie? {
        return transaction {
            MoviesTable.selectAll()
                .where { MoviesTable.filmowId eq filmowId }
                .firstOrNull()
                ?.let { rowToMovie(it) }
        }
    }

    override fun findByLetterboxdId(letterboxdId: String): Movie? {
        return transaction {
            MoviesTable.selectAll()
                .where { MoviesTable.letterboxdId eq letterboxdId }
                .firstOrNull()
                ?.let { rowToMovie(it) }
        }
    }

    override fun getDbIdByFilmowId(filmowId: String): Long? {
        return transaction {
            MoviesTable.selectAll()
                .where { MoviesTable.filmowId eq filmowId }
                .firstOrNull()
                ?.get(MoviesTable.id)?.value
        }
    }

    override fun upsertMovie(movie: Movie, filmowId: String?, letterboxdId: String?): Long {
        return transaction {
            // 1. Check by external source ID first (handles resolved movies whose tmdbId changed)
            val bySourceId = when {
                filmowId != null -> MoviesTable.selectAll()
                    .where { MoviesTable.filmowId eq filmowId }
                    .firstOrNull()
                letterboxdId != null -> MoviesTable.selectAll()
                    .where { MoviesTable.letterboxdId eq letterboxdId }
                    .firstOrNull()
                else -> null
            }

            // 2. Fall back to tmdbId lookup
            val existing = bySourceId ?: movie.tmdbId?.let { tid ->
                MoviesTable.selectAll()
                    .where { MoviesTable.tmdbId eq tid }
                    .firstOrNull()
            }

            if (existing != null) {
                val dbId = existing[MoviesTable.id].value
                MoviesTable.update({ MoviesTable.id eq dbId }) {
                    // A Movie's overview is always Portuguese (pt-BR lists, Filmow).
                    if (movie.overview.isNotBlank()) {
                        it[overview] = movie.overview
                        it[overviewLanguage] = null
                        it[overviewTranslated] = false
                    }
                    if (movie.posterPath != null) it[posterPath] = movie.posterPath
                    if (movie.backdropPath != null) it[backdropPath] = movie.backdropPath
                    if (movie.voteAverage > 0.0) it[voteAverage] = movie.voteAverage.toFloat()
                    if (movie.releaseDate != null) it[releaseDate] = movie.releaseDate
                    if (movie.localTitle != null) it[localTitle] = movie.localTitle
                    if (movie.originalTitle != null) it[originalTitle] = movie.originalTitle
                    if (filmowId != null) it[MoviesTable.filmowId] = filmowId
                    if (letterboxdId != null) it[MoviesTable.letterboxdId] = letterboxdId
                }
                dbId
            } else {
                MoviesTable.insertAndGetId {
                    it[tmdbId] = movie.tmdbId
                    it[title] = movie.title
                    it[localTitle] = movie.localTitle
                    it[originalTitle] = movie.originalTitle
                    it[year] = movie.releaseDate?.take(4)?.toIntOrNull()
                    it[posterPath] = movie.posterPath
                    it[backdropPath] = movie.backdropPath
                    it[overview] = movie.overview.takeIf { o -> o.isNotBlank() }
                    it[voteAverage] = movie.voteAverage.takeIf { v -> v > 0.0 }?.toFloat()
                    it[releaseDate] = movie.releaseDate
                    it[MoviesTable.filmowId] = filmowId
                    it[MoviesTable.letterboxdId] = letterboxdId
                    it[needsEnrichment] = true
                    if (movie.tmdbId != null) {
                        it[matchStatus] = MovieMatchStatus.MATCHED.wireValue
                        it[matchMethod] = MovieMatchMethod.TMDB.wireValue
                    }
                }.value
            }
        }
    }

    override fun hasPortugueseOverview(tmdbId: Int): Boolean = transaction {
        val row = MoviesTable.selectAll().where { MoviesTable.tmdbId eq tmdbId }.firstOrNull()
            ?: return@transaction false
        overviewRank(row[MoviesTable.overview], row[MoviesTable.overviewLanguage], row[MoviesTable.overviewTranslated]) >= 2
    }

    override fun enrichMovie(tmdbId: Int, detail: MovieDetail, credits: TmdbCredits?) {
        transaction {
            val movieRow = MoviesTable.selectAll()
                .where { MoviesTable.tmdbId eq tmdbId }
                .firstOrNull() ?: return@transaction

            val movieDbId = movieRow[MoviesTable.id].value
            // A new overview never replaces a better one already kept.
            val replacesOverview = detail.overview.isNotBlank() &&
                overviewRank(detail.overview, detail.overviewLanguage, detail.overviewTranslated) >=
                overviewRank(
                    movieRow[MoviesTable.overview],
                    movieRow[MoviesTable.overviewLanguage],
                    movieRow[MoviesTable.overviewTranslated]
                )

            MoviesTable.update({ MoviesTable.tmdbId eq tmdbId }) {
                if (replacesOverview) {
                    it[overview] = detail.overview
                    it[overviewLanguage] = detail.overviewLanguage
                    it[overviewTranslated] = detail.overviewTranslated
                }
                it[backdropPath] = detail.backdropPath
                it[releaseDate] = detail.releaseDate
                it[runtime] = detail.runtime
                it[tagline] = detail.tagline
                it[voteAverage] = detail.voteAverage.toFloat()
                it[posterPath] = detail.posterPath ?: movieRow[MoviesTable.posterPath]
                it[year] = detail.releaseDate?.take(4)?.toIntOrNull()
                it[enrichedAt] = Clock.System.now()
                it[needsEnrichment] = false
                if (detail.trailerKey != null) it[trailerKey] = detail.trailerKey
                it[matchStatus] = MovieMatchStatus.MATCHED.wireValue
                if (movieRow[MoviesTable.matchMethod] == null) it[matchMethod] = MovieMatchMethod.TMDB.wireValue
                it[enrichmentAttempts] = 0
                it[enrichmentRetryAt] = null
            }

            syncGenres(movieDbId, detail.genres)

            if (credits != null) {
                syncCredits(movieDbId, credits)
            }
        }
    }

    override fun saveMovieFacts(tmdbId: Int, originalLanguage: String?, countries: List<String>, keywords: List<Pair<Int, String>>) {
        transaction {
            val movieDbId = MoviesTable.select(MoviesTable.id)
                .where { MoviesTable.tmdbId eq tmdbId }
                .firstOrNull()
                ?.get(MoviesTable.id)?.value ?: return@transaction
            MoviesTable.update({ MoviesTable.id eq movieDbId }) {
                it[MoviesTable.originalLanguage] = originalLanguage?.take(10)
            }
            MovieCountriesTable.deleteWhere { movieId eq movieDbId }
            countries.filter { it.length == 2 }.map { it.uppercase() }.distinct().forEach { code ->
                MovieCountriesTable.insert {
                    it[movieId] = movieDbId
                    it[country] = code
                }
            }
            MovieKeywordsTable.deleteWhere { movieId eq movieDbId }
            keywords.distinctBy { it.first }.forEach { (keywordId, name) ->
                MovieKeywordsTable.insert {
                    it[movieId] = movieDbId
                    it[MovieKeywordsTable.keywordId] = keywordId
                    it[MovieKeywordsTable.name] = name.take(255)
                }
            }
        }
    }

    override fun resolveScrapedMovie(oldDbId: Long, realTmdbId: Int, filmowId: String?) {
        transaction {
            val existingReal = MoviesTable.selectAll()
                .where { MoviesTable.tmdbId eq realTmdbId }
                .firstOrNull()

            if (existingReal != null) {
                val realDbId = existingReal[MoviesTable.id].value
                if (realDbId == oldDbId) return@transaction

                // Copy source-specific data from placeholder to real row
                val oldRow = MoviesTable.selectAll()
                    .where { MoviesTable.id eq oldDbId }
                    .firstOrNull()

                MoviesTable.update({ MoviesTable.id eq realDbId }) {
                    if (filmowId != null) it[MoviesTable.filmowId] = filmowId
                    val oldLocalTitle = oldRow?.get(MoviesTable.localTitle)
                    if (oldLocalTitle != null && existingReal[MoviesTable.localTitle] == null) {
                        it[localTitle] = oldLocalTitle
                    }
                }

                // Re-point UserMovies references, handling duplicates (one row per user and movie)
                val usersWithRealMovie = UserMoviesTable.selectAll()
                    .where { UserMoviesTable.movieId eq realDbId }
                    .map { it[UserMoviesTable.userId].value }
                    .toSet()

                val oldUserMovies = UserMoviesTable.selectAll()
                    .where { UserMoviesTable.movieId eq oldDbId }
                    .toList()

                for (row in oldUserMovies) {
                    val hasDuplicate = row[UserMoviesTable.userId].value in usersWithRealMovie
                    if (hasDuplicate) {
                        UserMoviesTable.deleteWhere { UserMoviesTable.id eq row[UserMoviesTable.id] }
                    } else {
                        UserMoviesTable.update({ UserMoviesTable.id eq row[UserMoviesTable.id] }) {
                            it[movieId] = realDbId
                        }
                    }
                }

                // Re-point list items, handling duplicates
                val existingListItems = ShelfItemsTable.selectAll()
                    .where { ShelfItemsTable.movieId eq realDbId }
                    .map { it[ShelfItemsTable.shelfId].value }
                    .toSet()

                val oldListItems = ShelfItemsTable.selectAll()
                    .where { ShelfItemsTable.movieId eq oldDbId }
                    .toList()

                for (row in oldListItems) {
                    val listId = row[ShelfItemsTable.shelfId].value
                    if (existingListItems.contains(listId)) {
                        ShelfItemsTable.deleteWhere { ShelfItemsTable.id eq row[ShelfItemsTable.id] }
                    } else {
                        ShelfItemsTable.update({ ShelfItemsTable.id eq row[ShelfItemsTable.id] }) {
                            it[movieId] = realDbId
                        }
                    }
                }

                // Re-point watch logs, dropping imported ones the real movie already has
                val importedOnReal = WatchLogsTable.selectAll()
                    .where { (WatchLogsTable.movieId eq realDbId) and WatchLogsTable.externalId.isNotNull() }
                    .map { Triple(it[WatchLogsTable.userId].value, it[WatchLogsTable.importSource], it[WatchLogsTable.externalId]) }
                    .toSet()
                for (row in WatchLogsTable.selectAll().where { WatchLogsTable.movieId eq oldDbId }.toList()) {
                    val key = Triple(row[WatchLogsTable.userId].value, row[WatchLogsTable.importSource], row[WatchLogsTable.externalId])
                    if (row[WatchLogsTable.externalId] != null && key in importedOnReal) {
                        WatchLogsTable.deleteWhere { WatchLogsTable.id eq row[WatchLogsTable.id] }
                    } else {
                        WatchLogsTable.update({ WatchLogsTable.id eq row[WatchLogsTable.id] }) { it[movieId] = realDbId }
                    }
                }
                UsersTable.update({ UsersTable.watchingNowMovieId eq oldDbId }) { it[watchingNowMovieId] = realDbId }
                CineClubeSessionsTable.update({ CineClubeSessionsTable.movieId eq oldDbId }) { it[movieId] = realDbId }

                // Delete orphaned placeholder (respect FK order)
                MovieSimilarsTable.deleteWhere { MovieSimilarsTable.movieId eq oldDbId }
                MovieWatchProvidersTable.deleteWhere { MovieWatchProvidersTable.movieId eq oldDbId }
                MovieCastTable.deleteWhere { MovieCastTable.movieId eq oldDbId }
                MovieGenresTable.deleteWhere { MovieGenresTable.movieId eq oldDbId }
                MoviesTable.deleteWhere { MoviesTable.id eq oldDbId }
            } else {
                MoviesTable.update({ MoviesTable.id eq oldDbId }) {
                    it[tmdbId] = realTmdbId
                    if (filmowId != null) it[MoviesTable.filmowId] = filmowId
                }
            }
        }
    }

    override fun getMoviesNeedingEnrichment(limit: Int): List<EnrichmentCandidate> {
        return transaction {
            val now = Clock.System.now()
            MoviesTable.selectAll()
                .where {
                    (MoviesTable.needsEnrichment eq true) and
                        (MoviesTable.matchStatus neq MovieMatchStatus.UNMATCHED.wireValue) and
                        (MoviesTable.enrichmentRetryAt.isNull() or (MoviesTable.enrichmentRetryAt lessEq now))
                }
                // Fresh movies first: the ones that keep failing wait behind them.
                .orderBy(MoviesTable.enrichmentAttempts to SortOrder.ASC, MoviesTable.id to SortOrder.ASC)
                .limit(limit)
                .map(::rowToEnrichmentCandidate)
        }
    }

    override fun getEnrichmentCandidates(dbIds: Collection<Long>): List<EnrichmentCandidate> {
        if (dbIds.isEmpty()) return emptyList()
        return transaction {
            dbIds.distinct().chunked(QUERY_CHUNK).flatMap { chunk ->
                MoviesTable.selectAll()
                    .where { MoviesTable.id inList chunk }
                    .orderBy(MoviesTable.id, SortOrder.ASC)
                    .map(::rowToEnrichmentCandidate)
            }
        }
    }

    private fun rowToEnrichmentCandidate(row: ResultRow): EnrichmentCandidate {
        val tmdbId = row[MoviesTable.tmdbId]
        return EnrichmentCandidate(
            dbId = row[MoviesTable.id].value,
            tmdbId = tmdbId,
            title = row[MoviesTable.title],
            originalTitle = row[MoviesTable.originalTitle],
            localTitle = row[MoviesTable.localTitle],
            year = row[MoviesTable.year],
            filmowId = row[MoviesTable.filmowId],
            imdbUrl = row[MoviesTable.imdbUrl],
            director = row[MoviesTable.director],
            needsResolution = tmdbId == null,
            needsEnrichment = row[MoviesTable.needsEnrichment],
            unmatched = row[MoviesTable.matchStatus] == MovieMatchStatus.UNMATCHED.wireValue
        )
    }

    override fun markMatched(tmdbId: Int, method: MovieMatchMethod) {
        transaction {
            MoviesTable.update({ MoviesTable.tmdbId eq tmdbId }) {
                it[matchStatus] = MovieMatchStatus.MATCHED.wireValue
                it[matchMethod] = method.wireValue
                it[enrichmentAttempts] = 0
                it[enrichmentRetryAt] = null
            }
        }
    }

    override fun markUnmatched(dbId: Long) {
        transaction {
            MoviesTable.update({ (MoviesTable.id eq dbId) and MoviesTable.tmdbId.isNull() }) {
                it[matchStatus] = MovieMatchStatus.UNMATCHED.wireValue
                it[needsEnrichment] = false
            }
        }
    }

    override fun markEnrichmentFailed(dbId: Long) {
        transaction {
            val attempts = MoviesTable.select(MoviesTable.enrichmentAttempts)
                .where { MoviesTable.id eq dbId }
                .firstOrNull()?.get(MoviesTable.enrichmentAttempts) ?: return@transaction
            val next = attempts + 1
            MoviesTable.update({ MoviesTable.id eq dbId }) {
                it[enrichmentAttempts] = next
                it[enrichmentRetryAt] = Clock.System.now() + retryDelay(next)
            }
        }
    }

    override fun getResolvableFilmowIds(): Set<String> {
        return transaction {
            MoviesTable.select(MoviesTable.filmowId)
                .where { MoviesTable.filmowId.isNotNull() and (MoviesTable.tmdbId.isNotNull() or MoviesTable.imdbUrl.isNotNull()) }
                .mapNotNull { it[MoviesTable.filmowId] }
                .toSet()
        }
    }

    override fun findDbIdsByFilmowIds(filmowIds: Collection<String>): List<Long> {
        if (filmowIds.isEmpty()) return emptyList()
        return transaction {
            filmowIds.distinct().chunked(QUERY_CHUNK).flatMap { chunk ->
                MoviesTable.select(MoviesTable.id)
                    .where { MoviesTable.filmowId inList chunk }
                    .map { it[MoviesTable.id].value }
            }
        }
    }

    override fun getLocalMovieDetail(tmdbId: Int): MovieDetail? {
        return transaction {
            val row = MoviesTable.selectAll()
                .where { MoviesTable.tmdbId eq tmdbId }
                .firstOrNull() ?: return@transaction null

            val enrichedAt = row[MoviesTable.enrichedAt] ?: return@transaction null
            val staleThreshold = Clock.System.now().minus(kotlin.time.Duration.parse("7d"))
            if (enrichedAt < staleThreshold) {
                // Mark for re-enrichment but still return local data
                MoviesTable.update({ MoviesTable.id eq row[MoviesTable.id] }) {
                    it[needsEnrichment] = true
                }
            }

            val movieDbId = row[MoviesTable.id].value

            val genres = (MovieGenresTable innerJoin GenresTable)
                .selectAll()
                .where { MovieGenresTable.movieId eq movieDbId }
                .map { it[GenresTable.name] }

            val director = runCatching {
                (MovieCastTable innerJoin PeopleTable)
                    .selectAll()
                    .where { (MovieCastTable.movieId eq movieDbId) and (MovieCastTable.role eq "director") }
                    .firstOrNull()
                    ?.let { r -> Person(id = r[PeopleTable.id].value, tmdbPersonId = r[PeopleTable.tmdbId], name = r[PeopleTable.name], profilePath = r[PeopleTable.profilePath]) }
            }.getOrElse { catalogLog.warn("director read failed: ${it.message}"); null }

            val writers = runCatching {
                (MovieCastTable innerJoin PeopleTable)
                    .selectAll()
                    .where { (MovieCastTable.movieId eq movieDbId) and (MovieCastTable.role eq "writer") }
                    .orderBy(MovieCastTable.position, SortOrder.ASC)
                    .map { r -> Person(id = r[PeopleTable.id].value, tmdbPersonId = r[PeopleTable.tmdbId], name = r[PeopleTable.name], profilePath = r[PeopleTable.profilePath]) }
            }.getOrElse { catalogLog.warn("writers read failed: ${it.message}"); emptyList() }

            val cast = runCatching {
                (MovieCastTable innerJoin PeopleTable)
                    .selectAll()
                    .where { (MovieCastTable.movieId eq movieDbId) and (MovieCastTable.role eq "actor") }
                    .orderBy(MovieCastTable.position, SortOrder.ASC)
                    .limit(10)
                    .map { r ->
                        CastMember(
                            person = Person(id = r[PeopleTable.id].value, tmdbPersonId = r[PeopleTable.tmdbId], name = r[PeopleTable.name], profilePath = r[PeopleTable.profilePath]),
                            character = r[MovieCastTable.character],
                            order = r[MovieCastTable.position]
                        )
                    }
            }.getOrElse { catalogLog.warn("cast read failed: ${it.message}"); emptyList() }

            val similars = resolveSimilarMovies(movieDbId)

            val providers = MovieWatchProvidersTable.selectAll()
                .where { MovieWatchProvidersTable.movieId eq movieDbId }
                .map { r ->
                    WatchProvider(
                        name = r[MovieWatchProvidersTable.providerName],
                        logoPath = r[MovieWatchProvidersTable.logoPath]
                    )
                }

            MovieDetail(
                id = movieDbId,
                tmdbId = row[MoviesTable.tmdbId],
                title = row[MoviesTable.title],
                localTitle = row[MoviesTable.localTitle],
                originalTitle = row[MoviesTable.originalTitle],
                overview = row[MoviesTable.overview] ?: "",
                overviewLanguage = row[MoviesTable.overviewLanguage],
                overviewTranslated = row[MoviesTable.overviewTranslated],
                posterPath = row[MoviesTable.posterPath],
                backdropPath = row[MoviesTable.backdropPath],
                voteAverage = row[MoviesTable.voteAverage]?.toDouble() ?: 0.0,
                releaseDate = row[MoviesTable.releaseDate],
                tagline = row[MoviesTable.tagline],
                runtime = row[MoviesTable.runtime],
                genres = genres,
                director = director,
                writers = writers,
                cast = cast,
                trailerKey = row[MoviesTable.trailerKey],
                watchProviders = providers,
                similarMovies = similars
            )
        }
    }

    override fun saveSimilarMovies(tmdbId: Int, similars: List<Movie>) {
        transaction {
            val movieDbId = MoviesTable.selectAll()
                .where { MoviesTable.tmdbId eq tmdbId }
                .firstOrNull()?.get(MoviesTable.id)?.value ?: return@transaction

            val now = Clock.System.now()
            for (movie in similars) {
                val similarTmdbId = movie.tmdbId ?: continue

                // Ensure the similar movie exists in MoviesTable so it gets a DB id
                MoviesTable.upsert(MoviesTable.tmdbId) {
                    it[MoviesTable.tmdbId] = similarTmdbId
                    it[MoviesTable.title] = movie.title
                    it[MoviesTable.originalTitle] = movie.originalTitle
                    it[MoviesTable.posterPath] = movie.posterPath
                    it[MoviesTable.backdropPath] = movie.backdropPath
                    it[MoviesTable.voteAverage] = movie.voteAverage.takeIf { v -> v > 0.0 }?.toFloat()
                    it[MoviesTable.releaseDate] = movie.releaseDate
                    it[MoviesTable.year] = movie.releaseDate?.take(4)?.toIntOrNull()
                    it[MoviesTable.needsEnrichment] = true
                }

                MovieSimilarsTable.upsert(MovieSimilarsTable.movieId, MovieSimilarsTable.similarTmdbId) {
                    it[MovieSimilarsTable.movieId] = movieDbId
                    it[MovieSimilarsTable.similarTmdbId] = similarTmdbId
                    it[MovieSimilarsTable.title] = movie.title
                    it[MovieSimilarsTable.posterPath] = movie.posterPath
                    it[MovieSimilarsTable.voteAverage] = movie.voteAverage.takeIf { v -> v > 0.0 }?.toFloat()
                    it[MovieSimilarsTable.releaseDate] = movie.releaseDate
                    it[MovieSimilarsTable.fetchedAt] = now
                }
            }
        }
    }

    override fun saveWatchProviders(tmdbId: Int, providers: List<WatchProvider>) {
        transaction {
            val movieDbId = MoviesTable.selectAll()
                .where { MoviesTable.tmdbId eq tmdbId }
                .firstOrNull()?.get(MoviesTable.id)?.value ?: return@transaction

            val now = Clock.System.now()
            // Clear old providers and insert fresh
            MovieWatchProvidersTable.deleteWhere { MovieWatchProvidersTable.movieId eq movieDbId }
            for (provider in providers) {
                MovieWatchProvidersTable.insert {
                    it[movieId] = movieDbId
                    it[providerName] = provider.name
                    it[logoPath] = provider.logoPath
                    it[fetchedAt] = now
                }
            }
        }
    }

    override fun getSimilarMovies(tmdbId: Int): CachedData<List<Movie>> {
        return transaction {
            val movieDbId = MoviesTable.selectAll()
                .where { MoviesTable.tmdbId eq tmdbId }
                .firstOrNull()?.get(MoviesTable.id)?.value
                ?: return@transaction CachedData(emptyList(), isStale = true)

            val rows = MovieSimilarsTable.selectAll()
                .where { MovieSimilarsTable.movieId eq movieDbId }
                .toList()

            if (rows.isEmpty()) return@transaction CachedData(emptyList(), isStale = true)

            val oldestFetch = rows.minOf { it[MovieSimilarsTable.fetchedAt] }
            val staleThreshold = Clock.System.now().minus(kotlin.time.Duration.parse("7d"))
            val isStale = oldestFetch < staleThreshold

            val movies = resolveSimilarMovies(movieDbId)
            CachedData(movies, isStale)
        }
    }

    override fun getWatchProviders(tmdbId: Int): CachedData<List<WatchProvider>> {
        return transaction {
            val movieDbId = MoviesTable.selectAll()
                .where { MoviesTable.tmdbId eq tmdbId }
                .firstOrNull()?.get(MoviesTable.id)?.value
                ?: return@transaction CachedData(emptyList(), isStale = true)

            val rows = MovieWatchProvidersTable.selectAll()
                .where { MovieWatchProvidersTable.movieId eq movieDbId }
                .toList()

            if (rows.isEmpty()) return@transaction CachedData(emptyList(), isStale = true)

            val oldestFetch = rows.minOf { it[MovieWatchProvidersTable.fetchedAt] }
            val staleThreshold = Clock.System.now().minus(kotlin.time.Duration.parse("7d"))
            val isStale = oldestFetch < staleThreshold

            val providers = rows.map { r ->
                WatchProvider(
                    name = r[MovieWatchProvidersTable.providerName],
                    logoPath = r[MovieWatchProvidersTable.logoPath]
                )
            }
            CachedData(providers, isStale)
        }
    }

    override fun cacheMovieListWithIds(movies: List<Movie>): List<Movie> {
        return transaction {
            movies.map { movie ->
                val tid = movie.tmdbId ?: return@map movie
                val existing = MoviesTable.selectAll()
                    .where { MoviesTable.tmdbId eq tid }
                    .firstOrNull()

                val dbId = if (existing != null) {
                    val id = existing[MoviesTable.id].value
                    MoviesTable.update({ MoviesTable.id eq id }) {
                        if (movie.posterPath != null) it[posterPath] = movie.posterPath
                        if (movie.backdropPath != null) it[backdropPath] = movie.backdropPath
                        if (movie.voteAverage > 0.0) it[voteAverage] = movie.voteAverage.toFloat()
                        if (movie.releaseDate != null) it[releaseDate] = movie.releaseDate
                        if (movie.overview.isNotBlank()) {
                            it[overview] = movie.overview
                            it[overviewLanguage] = null
                            it[overviewTranslated] = false
                        }
                    }
                    id
                } else {
                    val hasBasicData = movie.overview.isNotBlank()
                    MoviesTable.insertAndGetId {
                        it[tmdbId] = tid
                        it[title] = movie.title
                        it[originalTitle] = movie.originalTitle
                        it[year] = movie.releaseDate?.take(4)?.toIntOrNull()
                        it[posterPath] = movie.posterPath
                        it[backdropPath] = movie.backdropPath
                        it[overview] = movie.overview.takeIf { o -> o.isNotBlank() }
                        it[voteAverage] = movie.voteAverage.takeIf { v -> v > 0.0 }?.toFloat()
                        it[releaseDate] = movie.releaseDate
                        it[needsEnrichment] = !hasBasicData
                    }.value
                }
                movie.copy(id = dbId)
            }
        }
    }

    private fun syncGenres(movieDbId: Long, genreNames: List<String>) {
        for (name in genreNames) {
            val genreDbId = GenresTable.selectAll()
                .where { GenresTable.name eq name }
                .firstOrNull()
                ?.get(GenresTable.id)?.value ?: continue

            MovieGenresTable.upsert(MovieGenresTable.movieId, MovieGenresTable.genreId) {
                it[movieId] = movieDbId
                it[genreId] = genreDbId
            }
        }
    }

    private fun syncCredits(movieDbId: Long, credits: TmdbCredits) {
        // Directors
        val directors = credits.crew.filter { it.job == "Director" }
        for ((idx, crew) in directors.withIndex()) {
            val personTmdbId = crew.id ?: continue
            val personDbId = ensurePerson(personTmdbId, crew.name ?: "", crew.profilePath)
            upsertCastEntry(movieDbId, personDbId, "director", null, idx)
        }

        // Writers
        val writers = credits.crew.filter { it.job == "Writer" || it.job == "Screenplay" }
        for ((idx, crew) in writers.distinctBy { it.id }.withIndex()) {
            val personTmdbId = crew.id ?: continue
            val personDbId = ensurePerson(personTmdbId, crew.name ?: "", crew.profilePath)
            upsertCastEntry(movieDbId, personDbId, "writer", null, idx)
        }

        // Top 20 actors
        val actors = credits.cast.take(20)
        for (cast in actors) {
            val personTmdbId = cast.id ?: continue
            val personDbId = ensurePerson(personTmdbId, cast.name ?: "", cast.profilePath)
            upsertCastEntry(movieDbId, personDbId, "actor", cast.character, cast.order ?: 0)
        }
    }

    private fun upsertCastEntry(movieDbId: Long, personDbId: Long, role: String, character: String?, position: Int) {
        val now = Clock.System.now()
        MovieCastTable.upsert(MovieCastTable.movieId, MovieCastTable.personId, MovieCastTable.role) {
            it[movieId] = movieDbId
            it[personId] = personDbId
            it[MovieCastTable.role] = role
            it[MovieCastTable.character] = character
            it[MovieCastTable.position] = position
            it[MovieCastTable.fetchedAt] = now
        }
    }

    private fun ensurePerson(tmdbId: Int, name: String, profilePath: String?): Long {
        val existing = PeopleTable.selectAll()
            .where { PeopleTable.tmdbId eq tmdbId }
            .firstOrNull()

        if (existing != null) {
            val dbId = existing[PeopleTable.id].value
            // Update profile path if we have a new one
            if (profilePath != null && existing[PeopleTable.profilePath] == null) {
                PeopleTable.update({ PeopleTable.id eq dbId }) {
                    it[PeopleTable.profilePath] = profilePath
                }
            }
            return dbId
        }

        return PeopleTable.insertAndGetId {
            it[PeopleTable.tmdbId] = tmdbId
            it[PeopleTable.name] = name
            it[PeopleTable.profilePath] = profilePath
            it[PeopleTable.fetchedAt] = Clock.System.now()
        }.value
    }

    /**
     * Loads similar movies for a given DB id, ensuring each has an entry in MoviesTable.
     * Movies that exist only in MovieSimilarsTable (pre-fix cache) are upserted on the fly.
     */
    private fun resolveSimilarMovies(movieDbId: Long): List<Movie> {
        val rows = MovieSimilarsTable.selectAll()
            .where { MovieSimilarsTable.movieId eq movieDbId }
            .toList()

        if (rows.isEmpty()) return emptyList()

        // Build a map of tmdbId -> existing DB id from MoviesTable
        val similarTmdbIds = rows.map { it[MovieSimilarsTable.similarTmdbId] }
        val existingIds: Map<Int, Long> = MoviesTable.selectAll()
            .where { MoviesTable.tmdbId inList similarTmdbIds }
            .associate { it[MoviesTable.tmdbId]!! to it[MoviesTable.id].value }

        return rows.map { r ->
            val similarTmdbId = r[MovieSimilarsTable.similarTmdbId]
            val dbId = existingIds[similarTmdbId]
                ?: MoviesTable.upsert(MoviesTable.tmdbId) {
                    it[MoviesTable.tmdbId] = similarTmdbId
                    it[MoviesTable.title] = r[MovieSimilarsTable.title]
                    it[MoviesTable.posterPath] = r[MovieSimilarsTable.posterPath]
                    it[MoviesTable.voteAverage] = r[MovieSimilarsTable.voteAverage]
                    it[MoviesTable.releaseDate] = r[MovieSimilarsTable.releaseDate]
                    it[MoviesTable.year] = r[MovieSimilarsTable.releaseDate]?.take(4)?.toIntOrNull()
                    it[MoviesTable.needsEnrichment] = true
                }[MoviesTable.id].value
            Movie(
                id = dbId,
                tmdbId = similarTmdbId,
                title = r[MovieSimilarsTable.title],
                posterPath = r[MovieSimilarsTable.posterPath],
                voteAverage = r[MovieSimilarsTable.voteAverage]?.toDouble() ?: 0.0,
                releaseDate = r[MovieSimilarsTable.releaseDate]
            )
        }
    }

    override fun saveFilmowPartials(details: Map<String, FilmowMoviePartial>) {
        if (details.isEmpty()) return
        transaction {
            for ((filmowId, partial) in details) {
                MoviesTable.update({ MoviesTable.filmowId eq filmowId }) {
                    if (partial.imdbUrl != null) it[imdbUrl] = partial.imdbUrl
                    if (partial.runtime != null) it[runtime] = partial.runtime
                    if (partial.director != null) it[director] = partial.director
                    if (partial.genres.isNotEmpty()) it[filmowGenres] = partial.genres.joinToString(",")
                }
            }
        }
    }

    override fun getLocalMovieDetailByDbId(dbId: Long): MovieDetail? {
        return transaction {
            val row = MoviesTable.selectAll()
                .where { MoviesTable.id eq dbId }
                .firstOrNull() ?: return@transaction null

            // Prefer TMDB-sourced genres/cast if enriched, fall back to Filmow-scraped data
            val tmdbId = row[MoviesTable.tmdbId]

            val genres = if (tmdbId != null) {
                (MovieGenresTable innerJoin GenresTable)
                    .selectAll()
                    .where { MovieGenresTable.movieId eq dbId }
                    .map { it[GenresTable.name] }
                    .ifEmpty { row[MoviesTable.filmowGenres]?.split(",")?.map { it.trim() } ?: emptyList() }
            } else {
                row[MoviesTable.filmowGenres]?.split(",")?.map { it.trim() } ?: emptyList()
            }

            val director = if (tmdbId != null) {
                runCatching {
                    (MovieCastTable innerJoin PeopleTable)
                        .selectAll()
                        .where { (MovieCastTable.movieId eq dbId) and (MovieCastTable.role eq "director") }
                        .firstOrNull()
                        ?.let { r -> Person(id = r[PeopleTable.id].value, tmdbPersonId = r[PeopleTable.tmdbId], name = r[PeopleTable.name], profilePath = r[PeopleTable.profilePath]) }
                        ?: row[MoviesTable.director]?.let { name -> Person(id = 0L, tmdbPersonId = null, name = name, profilePath = null) }
                }.getOrElse { catalogLog.warn("director read failed: ${it.message}"); null }
            } else {
                row[MoviesTable.director]?.let { name -> Person(id = 0L, tmdbPersonId = null, name = name, profilePath = null) }
            }

            val writers = if (tmdbId != null) {
                runCatching {
                    (MovieCastTable innerJoin PeopleTable)
                        .selectAll()
                        .where { (MovieCastTable.movieId eq dbId) and (MovieCastTable.role eq "writer") }
                        .orderBy(MovieCastTable.position, SortOrder.ASC)
                        .map { r -> Person(id = r[PeopleTable.id].value, tmdbPersonId = r[PeopleTable.tmdbId], name = r[PeopleTable.name], profilePath = r[PeopleTable.profilePath]) }
                }.getOrElse { catalogLog.warn("writers read failed: ${it.message}"); emptyList() }
            } else {
                emptyList()
            }

            val cast = if (tmdbId != null) {
                runCatching {
                    (MovieCastTable innerJoin PeopleTable)
                        .selectAll()
                        .where { (MovieCastTable.movieId eq dbId) and (MovieCastTable.role eq "actor") }
                        .orderBy(MovieCastTable.position, SortOrder.ASC)
                        .limit(10)
                        .map { r ->
                            CastMember(
                                person = Person(id = r[PeopleTable.id].value, tmdbPersonId = r[PeopleTable.tmdbId], name = r[PeopleTable.name], profilePath = r[PeopleTable.profilePath]),
                                character = r[MovieCastTable.character],
                                order = r[MovieCastTable.position]
                            )
                        }
                }.getOrElse { catalogLog.warn("cast read failed: ${it.message}"); emptyList() }
            } else {
                emptyList()
            }

            val similars = MovieSimilarsTable
                .join(MoviesTable, JoinType.LEFT, MovieSimilarsTable.similarTmdbId, MoviesTable.tmdbId)
                .selectAll()
                .where { MovieSimilarsTable.movieId eq dbId }
                .map { r ->
                    Movie(
                        id = r.getOrNull(MoviesTable.id)?.value ?: 0L,
                        tmdbId = r[MovieSimilarsTable.similarTmdbId],
                        title = r[MovieSimilarsTable.title],
                        posterPath = r[MovieSimilarsTable.posterPath],
                        voteAverage = r[MovieSimilarsTable.voteAverage]?.toDouble() ?: 0.0,
                        releaseDate = r[MovieSimilarsTable.releaseDate]
                    )
                }

            val providers = MovieWatchProvidersTable.selectAll()
                .where { MovieWatchProvidersTable.movieId eq dbId }
                .map { r ->
                    WatchProvider(
                        name = r[MovieWatchProvidersTable.providerName],
                        logoPath = r[MovieWatchProvidersTable.logoPath]
                    )
                }

            MovieDetail(
                id = dbId,
                tmdbId = tmdbId,
                title = row[MoviesTable.title],
                localTitle = row[MoviesTable.localTitle],
                originalTitle = row[MoviesTable.originalTitle],
                overview = row[MoviesTable.overview] ?: "",
                overviewLanguage = row[MoviesTable.overviewLanguage],
                overviewTranslated = row[MoviesTable.overviewTranslated],
                posterPath = row[MoviesTable.posterPath],
                backdropPath = row[MoviesTable.backdropPath],
                voteAverage = row[MoviesTable.voteAverage]?.toDouble() ?: 0.0,
                releaseDate = row[MoviesTable.releaseDate],
                tagline = row[MoviesTable.tagline],
                runtime = row[MoviesTable.runtime],
                genres = genres,
                director = director,
                writers = writers,
                cast = cast,
                trailerKey = row[MoviesTable.trailerKey],
                watchProviders = providers,
                similarMovies = similars,
                matchStatus = MovieMatchStatus.fromWireValue(row[MoviesTable.matchStatus]) ?: MovieMatchStatus.PENDING
            )
        }
    }

    override fun findByTitle(title: String): Movie? {
        return transaction {
            MoviesTable.selectAll()
                .where {
                    (MoviesTable.localTitle eq title) or
                        (MoviesTable.title eq title) or
                        (MoviesTable.originalTitle eq title)
                }
                .firstOrNull()
                ?.let { rowToMovie(it) }
        }
    }

    private fun rowToMovie(row: ResultRow): Movie {
        return Movie(
            id = row[MoviesTable.id].value,
            tmdbId = row[MoviesTable.tmdbId],
            title = row[MoviesTable.title],
            localTitle = row[MoviesTable.localTitle],
            originalTitle = row[MoviesTable.originalTitle],
            overview = row[MoviesTable.overview] ?: "",
            posterPath = row[MoviesTable.posterPath],
            backdropPath = row[MoviesTable.backdropPath],
            voteAverage = row[MoviesTable.voteAverage]?.toDouble() ?: 0.0,
            releaseDate = row[MoviesTable.releaseDate] ?: row[MoviesTable.year]?.toString(),
            filmowId = row[MoviesTable.filmowId]
        )
    }

    private companion object {
        const val QUERY_CHUNK = 500
        val MAX_RETRY_DELAY = (24 * 60).minutes

        /** 2, 4, 8... minutes after each failure, up to a day. */
        fun retryDelay(attempts: Int) = (1L shl attempts.coerceIn(1, 11)).minutes.coerceAtMost(MAX_RETRY_DELAY)
    }
}
