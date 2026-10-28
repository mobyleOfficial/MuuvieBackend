package org.mobyle.data.service

import org.mobyle.data.local.shelves.ShelfDataSource
import org.mobyle.data.remote.articles.ArticlesDataSource
import org.mobyle.data.remote.tmdb.TmdbDataSource
import org.mobyle.domain.policy.ArticleMentionPolicy
import org.mobyle.domain.policy.MentionCandidate
import org.slf4j.LoggerFactory

/**
 * Finds the movies a news article cites (F5.8) and stores them in article_movies. Runs after
 * each scrape over the articles not linked yet; a failed TMDB search skips that mention only.
 */
class ArticleMovieLinker(
    private val articlesDataSource: ArticlesDataSource,
    private val tmdbDataSource: TmdbDataSource,
    private val shelfDataSource: ShelfDataSource
) {
    private val log = LoggerFactory.getLogger(ArticleMovieLinker::class.java)

    suspend fun linkPending(limit: Int = BATCH_SIZE) {
        val articles = articlesDataSource.articlesToLink(limit)
        articles.forEach { article ->
            val links = mutableMapOf<Long, Float>()
            ArticleMentionPolicy.mentions(article.title, article.summary, article.content).forEach { mention ->
                val candidates = try {
                    tmdbDataSource.searchMovies(mention.title, 1, year = mention.year).results.map { result ->
                        MentionCandidate(
                            tmdbId = result.id,
                            title = result.title,
                            originalTitle = result.originalTitle,
                            year = result.releaseDate?.take(4)?.toIntOrNull(),
                            voteCount = result.voteCount ?: 0
                        )
                    }
                } catch (e: Exception) {
                    log.warn("Article ${article.id}: TMDB search failed for a mention", e)
                    return@forEach
                }
                val (candidate, confidence) = ArticleMentionPolicy.match(mention, candidates) ?: return@forEach
                val movieId = shelfDataSource.resolveMovieId(null, candidate.tmdbId, candidate.title) ?: return@forEach
                links[movieId] = maxOf(confidence, links[movieId] ?: 0f)
            }
            articlesDataSource.saveMovieLinks(article.id, links)
        }
        if (articles.isNotEmpty()) log.info("Linked movies of ${articles.size} articles")
    }

    private companion object {
        const val BATCH_SIZE = 100
    }
}
