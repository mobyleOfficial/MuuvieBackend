package org.mobyle.data.remote.articles

import org.mobyle.domain.model.Article
import org.mobyle.domain.model.ArticleMovie
import org.mobyle.domain.model.ArticleMovieState
import org.mobyle.domain.model.Page
import org.mobyle.domain.model.PageRequest

interface ArticlesDataSource {
    suspend fun getArticleById(articleId: Long): Article?
    suspend fun scrapeAndStore()

    // v1 (B5): source filter, cited movies and the linker's queue.
    fun getArticlesPage(source: String?, page: PageRequest): Page<Article>
    fun getSources(): List<String>
    /** The movies each article cites, most confident first. */
    fun articleMovies(articleIds: List<Long>): Map<Long, List<ArticleMovie>>
    fun movieStates(userId: String, movieIds: List<Long>): Map<Long, ArticleMovieState>
    /** Articles the linker has not looked at yet, newest first. */
    fun articlesToLink(limit: Int): List<Article>
    /** Saves the movies found in the article (movie id → confidence) and marks it as linked. */
    fun saveMovieLinks(articleId: Long, links: Map<Long, Float>)
}
