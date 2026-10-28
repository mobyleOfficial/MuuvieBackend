package org.mobyle.domain.usecase.articles

import kotlinx.coroutines.runBlocking
import org.mobyle.domain.model.Article
import org.mobyle.domain.model.NotFoundException
import org.mobyle.domain.model.Page
import org.mobyle.domain.model.PageRequest
import org.mobyle.domain.repository.ArticlesRepository

/** News of the Notícias tab (F5.8), newest first, optionally from one source, with the movies each cites. */
class GetArticlesPage(private val repository: ArticlesRepository) {
    operator fun invoke(source: String?, page: PageRequest): Page<Article> {
        val articles = repository.getArticlesPage(source?.takeIf { it.isNotBlank() }, page)
        val movies = repository.articleMovies(articles.results.map { it.id })
        return articles.copy(results = articles.results.map { it.copy(movies = movies[it.id].orEmpty()) })
    }
}

/** One article with its movies and, for a logged-in viewer, whether each is on Quero ver (F5.8). */
class GetArticle(private val repository: ArticlesRepository) {
    operator fun invoke(viewerId: String?, articleId: Long): Article {
        val article = runBlocking { repository.getArticleById(articleId) } ?: throw NotFoundException("Article not found")
        val movies = repository.articleMovies(listOf(articleId))[articleId].orEmpty()
        val states = viewerId?.let { repository.movieStates(it, movies.map { movie -> movie.movieId }) }.orEmpty()
        return article.copy(movies = movies.map { it.copy(me = states[it.movieId]) })
    }
}

/** The outlets the news come from, for the source filter. */
class GetArticleSources(private val repository: ArticlesRepository) {
    operator fun invoke(): List<String> = repository.getSources()
}
