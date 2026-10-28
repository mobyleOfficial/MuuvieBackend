package org.mobyle.routing

import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import org.mobyle.data.remote.auth.tryAuthenticateJWT
import org.mobyle.di.injection
import org.mobyle.domain.usecase.articles.GetArticle
import org.mobyle.domain.usecase.articles.GetArticleSources
import org.mobyle.domain.usecase.articles.GetArticlesPage
import org.mobyle.domain.usecase.auth.ValidateToken

/** News, paged with the Page envelope, filtered by `source` and with the cited movies (F5.8). */
fun Route.getArticlesRouting() {
    val getArticlesPage by injection<GetArticlesPage>()
    val getArticle by injection<GetArticle>()
    val getArticleSources by injection<GetArticleSources>()
    val validateToken by injection<ValidateToken>()

    get("/articles") {
        val page = call.pageRequest() ?: return@get
        call.respond(getArticlesPage(call.parameters["source"], page))
    }

    get("/articles/sources") {
        call.respond(getArticleSources())
    }

    get("/articles/{articleId}") {
        val articleId = call.longParameter("articleId") ?: return@get
        val viewerId = call.tryAuthenticateJWT(validateToken)?.claims?.userId
        call.respond(getArticle(viewerId, articleId))
    }
}
