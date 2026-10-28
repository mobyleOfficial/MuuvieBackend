package org.mobyle.data.repository

import org.mobyle.data.remote.articles.ArticlesDataSource
import org.mobyle.domain.model.Article
import org.mobyle.domain.model.ArticleMovie
import org.mobyle.domain.model.ArticleMovieState
import org.mobyle.domain.model.Page
import org.mobyle.domain.model.PageRequest
import org.mobyle.domain.repository.ArticlesRepository

class ArticlesRepositoryImpl(
    private val articlesDataSource: ArticlesDataSource
) : ArticlesRepository {

    override suspend fun getArticleById(articleId: Long): Article? {
        return articlesDataSource.getArticleById(articleId)
    }

    override fun getArticlesPage(source: String?, page: PageRequest): Page<Article> =
        articlesDataSource.getArticlesPage(source, page)

    override fun getSources(): List<String> = articlesDataSource.getSources()

    override fun articleMovies(articleIds: List<Long>): Map<Long, List<ArticleMovie>> =
        articlesDataSource.articleMovies(articleIds)

    override fun movieStates(userId: String, movieIds: List<Long>): Map<Long, ArticleMovieState> =
        articlesDataSource.movieStates(userId, movieIds)
}
