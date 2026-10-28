package org.mobyle.domain.repository

import org.mobyle.domain.model.Article
import org.mobyle.domain.model.ArticleMovie
import org.mobyle.domain.model.ArticleMovieState
import org.mobyle.domain.model.Page
import org.mobyle.domain.model.PageRequest

interface ArticlesRepository {
    suspend fun getArticleById(articleId: Long): Article?

    fun getArticlesPage(source: String?, page: PageRequest): Page<Article>
    fun getSources(): List<String>
    fun articleMovies(articleIds: List<Long>): Map<Long, List<ArticleMovie>>
    fun movieStates(userId: String, movieIds: List<Long>): Map<Long, ArticleMovieState>
}
