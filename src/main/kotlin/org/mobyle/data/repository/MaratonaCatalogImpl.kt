package org.mobyle.data.repository

import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.mobyle.data.local.database.GenresTable
import org.mobyle.data.local.database.MovieCastTable
import org.mobyle.data.local.database.MovieCountriesTable
import org.mobyle.data.local.database.MovieGenresTable
import org.mobyle.data.local.database.MovieKeywordsTable
import org.mobyle.data.local.database.MoviesTable
import org.mobyle.data.local.database.PeopleTable
import org.mobyle.data.local.movies.MovieCatalogDataSource
import org.mobyle.data.remote.tmdb.TmdbDataSource
import org.mobyle.data.remote.tmdb.toDomain
import org.mobyle.data.service.MovieEnrichmentService
import org.mobyle.domain.model.ChallengeCriteria
import org.mobyle.domain.model.CriteriaField
import org.mobyle.domain.model.Movie
import org.mobyle.domain.model.MovieFacts
import org.mobyle.domain.model.Page
import org.mobyle.domain.repository.MaratonaCatalog
import org.slf4j.LoggerFactory

// TMDB serves at most 500 pages of discover.
private const val MAX_PAGE = 500
// Leaves out titles with a handful of votes when the criteria ask for a score.
private const val MIN_VOTES_FOR_SCORE = 50

class MaratonaCatalogImpl(
    private val tmdb: TmdbDataSource,
    private val catalog: MovieCatalogDataSource,
    private val enrichment: MovieEnrichmentService
) : MaratonaCatalog {
    private val log = LoggerFactory.getLogger(MaratonaCatalogImpl::class.java)

    override fun facts(movieId: Long): MovieFacts? {
        val pending = transaction {
            MoviesTable.select(MoviesTable.tmdbId, MoviesTable.needsEnrichment)
                .where { MoviesTable.id eq movieId }
                .firstOrNull()
                ?.let { it[MoviesTable.tmdbId] to it[MoviesTable.needsEnrichment] }
        } ?: return null
        val (tmdbId, needsEnrichment) = pending
        if (needsEnrichment && tmdbId != null) {
            try {
                runBlocking { enrichment.enrichOnDemand(tmdbId) }
            } catch (e: Exception) {
                log.warn("[MARATONA] Could not enrich movie $tmdbId: ${e.message}")
            }
        }
        return transaction { readFacts(movieId) }
    }

    override fun suggestions(criteria: ChallengeCriteria, page: Int): Page<Movie> {
        val current = page.coerceIn(1, MAX_PAGE)
        val listing = runBlocking { tmdb.discoverWith(discoverParams(criteria), current) }.toDomain()
        val movies = runCatching { catalog.cacheMovieListWithIds(listing.movies) }.getOrElse { listing.movies }
        val totalPages = listing.totalPages.coerceAtMost(MAX_PAGE)
        return Page(movies, current, totalPages, listing.totalResults, current < totalPages)
    }

    private fun readFacts(movieId: Long): MovieFacts? {
        val movie = MoviesTable.selectAll().where { MoviesTable.id eq movieId }.firstOrNull() ?: return null
        val genres = (MovieGenresTable innerJoin GenresTable).select(GenresTable.tmdbId)
            .where { MovieGenresTable.movieId eq movieId }
            .map { it[GenresTable.tmdbId] }
            .toSet()
        val people = (MovieCastTable innerJoin PeopleTable).select(PeopleTable.tmdbId, MovieCastTable.role)
            .where { MovieCastTable.movieId eq movieId }
            .map { it[MovieCastTable.role] to it[PeopleTable.tmdbId] }
        return MovieFacts(
            movieId = movieId,
            genreIds = genres,
            year = movie[MoviesTable.year] ?: movie[MoviesTable.releaseDate]?.take(4)?.toIntOrNull(),
            countries = MovieCountriesTable.select(MovieCountriesTable.country)
                .where { MovieCountriesTable.movieId eq movieId }
                .map { it[MovieCountriesTable.country] }
                .toSet(),
            originalLanguage = movie[MoviesTable.originalLanguage],
            directorIds = people.filter { it.first == "director" }.map { it.second }.toSet(),
            castIds = people.filter { it.first == "actor" }.map { it.second }.toSet(),
            runtime = movie[MoviesTable.runtime],
            keywordIds = MovieKeywordsTable.select(MovieKeywordsTable.keywordId)
                .where { MovieKeywordsTable.movieId eq movieId }
                .map { it[MovieKeywordsTable.keywordId] }
                .toSet(),
            voteAverage = movie[MoviesTable.voteAverage]?.toDouble()
        )
    }

    companion object {
        /**
         * Translates criteria to TMDB discover parameters. Values of a rule are alternatives
         * ("|"); several genre rules must all match (","). Year and decade rules narrow one
         * date range.
         */
        fun discoverParams(criteria: ChallengeCriteria): Map<String, String> {
            val params = linkedMapOf("sort_by" to "popularity.desc")
            var from: Int? = null
            var to: Int? = null
            fun narrow(start: Int, end: Int) {
                from = maxOf(from ?: start, start)
                to = minOf(to ?: end, end)
            }
            val genres = mutableListOf<String>()
            criteria.all.forEach { rule ->
                when (CriteriaField.fromWireValue(rule.field)) {
                    CriteriaField.GENRE -> genres += rule.ints().joinToString("|")
                    CriteriaField.RELEASE_YEAR -> rule.ints().takeIf { it.size == 2 }?.let { (start, end) -> narrow(start, end) }
                    CriteriaField.DECADE -> rule.ints().takeIf { it.isNotEmpty() }?.let { narrow(it.min(), it.max() + 9) }
                    CriteriaField.COUNTRY -> params["with_origin_country"] = rule.strings().joinToString("|")
                    CriteriaField.ORIGINAL_LANGUAGE -> params["with_original_language"] = rule.strings().joinToString("|")
                    CriteriaField.DIRECTOR -> params["with_crew"] = rule.ints().joinToString("|")
                    CriteriaField.CAST -> params["with_cast"] = rule.ints().joinToString("|")
                    CriteriaField.KEYWORD -> params["with_keywords"] = rule.ints().joinToString("|")
                    CriteriaField.RUNTIME_MIN -> rule.int()?.let { params["with_runtime.gte"] = it.toString() }
                    CriteriaField.RUNTIME_MAX -> rule.int()?.let { params["with_runtime.lte"] = it.toString() }
                    CriteriaField.VOTE_AVERAGE_MIN -> rule.double()?.let {
                        params["vote_average.gte"] = it.toString()
                        params["vote_count.gte"] = MIN_VOTES_FOR_SCORE.toString()
                    }
                    null -> Unit
                }
            }
            if (genres.isNotEmpty()) params["with_genres"] = genres.joinToString(",")
            from?.let { params["primary_release_date.gte"] = "$it-01-01" }
            to?.let { params["primary_release_date.lte"] = "$it-12-31" }
            return params
        }
    }
}
