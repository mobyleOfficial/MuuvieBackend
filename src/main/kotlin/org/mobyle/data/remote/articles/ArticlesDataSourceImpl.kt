package org.mobyle.data.remote.articles

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import org.mobyle.data.local.database.ArticleMoviesTable
import org.mobyle.data.local.database.ArticlesTable
import org.mobyle.data.local.database.MoviesTable
import org.mobyle.data.local.database.UserMoviesTable
import org.mobyle.data.local.database.userDbIdOf
import org.mobyle.domain.model.Article
import org.mobyle.domain.model.ArticleMovie
import org.mobyle.domain.model.ArticleMovieState
import org.mobyle.domain.model.Page
import org.mobyle.domain.model.PageRequest
import org.mobyle.domain.model.vocabulary.WatchStatus
import org.slf4j.LoggerFactory
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil

class ArticlesDataSourceImpl : ArticlesDataSource {

    private val log = LoggerFactory.getLogger(ArticlesDataSourceImpl::class.java)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    companion object {
        private val G1_NOISE_PATTERNS = listOf(
            Regex("📱.*?do dia", RegexOption.IGNORE_CASE),
            Regex("^Favorite o g1.*", RegexOption.MULTILINE),
            Regex("^Agora no g1.*", RegexOption.MULTILINE),
            Regex("^Initial plugin text.*", RegexOption.MULTILINE),
            Regex("^LEIA TAMBÉM:.*", RegexOption.MULTILINE),
            Regex("^Assista ao teaser.*", RegexOption.MULTILINE),
            Regex("^Assista ao trailer.*", RegexOption.MULTILINE),
            Regex("^Veja o trailer.*", RegexOption.MULTILINE),
            Regex("^Agenda SP:.*", RegexOption.MULTILINE),
        )

        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }

    // region Public API

    override suspend fun scrapeAndStore() {
        scrapeG1()
        scrapeOmelete()
    }

    override suspend fun getArticleById(articleId: Long): Article? {
        return transaction {
            ArticlesTable
                .selectAll()
                .where { ArticlesTable.id eq articleId }
                .firstOrNull()
                ?.toArticle()
        }
    }

    override fun getArticlesPage(source: String?, page: PageRequest): Page<Article> = transaction {
        val query = ArticlesTable.selectAll()
        if (source != null) query.andWhere { ArticlesTable.sourceName eq source }
        val total = query.count().toInt()
        val articles = query
            .orderBy(ArticlesTable.publishedAt to SortOrder.DESC, ArticlesTable.id to SortOrder.DESC)
            .limit(page.limit, offset = page.offset)
            .map { row -> row.toArticle() }
        Page.of(articles, page, total)
    }

    override fun getSources(): List<String> = transaction {
        ArticlesTable.select(ArticlesTable.sourceName)
            .withDistinct()
            .orderBy(ArticlesTable.sourceName to SortOrder.ASC)
            .map { it[ArticlesTable.sourceName] }
    }

    override fun articleMovies(articleIds: List<Long>): Map<Long, List<ArticleMovie>> = transaction {
        if (articleIds.isEmpty()) return@transaction emptyMap()
        (ArticleMoviesTable innerJoin MoviesTable).selectAll()
            .where { ArticleMoviesTable.articleId inList articleIds }
            .orderBy(ArticleMoviesTable.confidence to SortOrder.DESC, MoviesTable.id to SortOrder.ASC)
            .groupBy({ it[ArticleMoviesTable.articleId].value }) { row ->
                ArticleMovie(
                    movieId = row[MoviesTable.id].value,
                    tmdbId = row[MoviesTable.tmdbId],
                    title = row[MoviesTable.localTitle] ?: row[MoviesTable.title],
                    posterPath = row[MoviesTable.posterPath],
                    year = row[MoviesTable.year]
                )
            }
    }

    override fun movieStates(userId: String, movieIds: List<Long>): Map<Long, ArticleMovieState> = transaction {
        val userDbId = userDbIdOf(userId)
        val statuses = if (userDbId == null || movieIds.isEmpty()) emptyMap() else UserMoviesTable.selectAll()
            .where { (UserMoviesTable.userId eq userDbId) and (UserMoviesTable.movieId inList movieIds) }
            .associate { it[UserMoviesTable.movieId].value to it[UserMoviesTable.status] }
        movieIds.associateWith { movieId ->
            ArticleMovieState(
                inQueroVer = statuses[movieId] == WatchStatus.WANT_TO_WATCH.wireValue,
                watched = statuses[movieId] == WatchStatus.WATCHED.wireValue
            )
        }
    }

    override fun articlesToLink(limit: Int): List<Article> = transaction {
        ArticlesTable.selectAll()
            .where { ArticlesTable.moviesLinkedAt.isNull() }
            .orderBy(ArticlesTable.publishedAt to SortOrder.DESC)
            .limit(limit)
            .map { row -> row.toArticle() }
    }

    override fun saveMovieLinks(articleId: Long, links: Map<Long, Float>) {
        transaction {
            ArticleMoviesTable.deleteWhere { ArticleMoviesTable.articleId eq articleId }
            links.forEach { (movieId, confidence) ->
                ArticleMoviesTable.insert {
                    it[ArticleMoviesTable.articleId] = articleId
                    it[ArticleMoviesTable.movieId] = movieId
                    it[ArticleMoviesTable.confidence] = confidence
                }
            }
            ArticlesTable.update({ ArticlesTable.id eq articleId }) { it[moviesLinkedAt] = Clock.System.now() }
        }
    }

    // endregion

    // region G1 Cinema (RSS)

    private fun scrapeG1() {
        try {
            val doc = Jsoup.connect("https://g1.globo.com/rss/g1/pop-arte/cinema/")
                .userAgent("MoovieAi/1.0")
                .timeout(15_000)
                .parser(Parser.xmlParser())
                .get()

            val items = doc.select("item")
            log.info("G1: found ${items.size} items")

            val now = Clock.System.now()

            for (item in items) {
                val title = item.selectFirst("title")?.text() ?: continue
                val link = item.selectFirst("link")?.text() ?: continue
                val subtitle = item.selectFirst("atom|subtitle")?.text() ?: ""
                val pubDateStr = item.selectFirst("pubDate")?.text()
                val imageUrl = item.selectFirst("media|content")?.attr("url")
                val publishedAt = parseRssDate(pubDateStr) ?: now

                if (articleExists(link)) continue

                val content = try {
                    scrapeG1ArticlePage(link)
                } catch (e: Exception) {
                    log.warn("G1: failed to scrape article: $link", e)
                    ""
                }

                insertArticle(link, title, subtitle, content, imageUrl, "G1 Cinema", publishedAt, now)
            }
        } catch (e: Exception) {
            log.error("G1: failed to scrape RSS feed", e)
        }
    }

    private fun scrapeG1ArticlePage(url: String): String {
        val doc = fetchPage(url)
        val paragraphs = mutableListOf<String>()
        val articleBody = doc.selectFirst(".mc-article-body") ?: return ""

        for (element in articleBody.select("p.content-text__container, .content-intertitle h2")) {
            val text = element.text().trim()
            if (text.isBlank()) continue
            if (G1_NOISE_PATTERNS.any { it.containsMatchIn(text) }) continue

            if (element.tagName() == "h2") {
                paragraphs.add("\n$text\n")
            } else {
                paragraphs.add(text)
            }
        }

        return paragraphs.joinToString("\n\n").trim()
    }

    // endregion

    // region Omelete (HTML scraping)

    private fun scrapeOmelete() {
        try {
            val listingDoc = fetchPage("https://www.omelete.com.br/filmes")

            val articleUrls = listingDoc.select("a[href^=/filmes/]")
                .map { it.attr("abs:href") }
                .filter { it.matches(Regex("https://www\\.omelete\\.com\\.br/filmes/[a-z0-9-]+")) }
                .distinct()

            log.info("Omelete: found ${articleUrls.size} article links")

            val now = Clock.System.now()

            for (url in articleUrls) {
                if (articleExists(url)) continue

                try {
                    scrapeOmeleteArticlePage(url, now)
                } catch (e: Exception) {
                    log.warn("Omelete: failed to scrape article: $url", e)
                }
            }
        } catch (e: Exception) {
            log.error("Omelete: failed to scrape listing page", e)
        }
    }

    private fun scrapeOmeleteArticlePage(url: String, now: Instant) {
        val doc = fetchPage(url)

        val jsonLd = extractJsonLd(doc)

        val title = jsonLd?.get("headline")?.jsonPrimitive?.content
            ?: doc.selectFirst("title")?.text()
            ?: return

        val summary = jsonLd?.get("description")?.jsonPrimitive?.content ?: ""

        val imageUrl = jsonLd?.get("image")?.let { imageElement ->
            when (imageElement) {
                is JsonArray -> imageElement.firstOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.content
                is JsonObject -> imageElement["url"]?.jsonPrimitive?.content
                else -> null
            }
        } ?: doc.selectFirst(".article__cover__image")?.attr("data-lazy-src")?.let { "https:$it" }

        val publishedAt = jsonLd?.get("datePublished")?.jsonPrimitive?.content?.let { parseIsoDate(it) } ?: now

        val paragraphs = mutableListOf<String>()
        val articleBody = doc.selectFirst(".article__body.article--content")

        if (articleBody != null) {
            // Remove known noisy sections before extracting text
            articleBody.select(".omelete-recommends, .webstories, .comments, .advertisement, .related-content").remove()

            for (element in articleBody.select("p, h2, h3")) {
                val text = element.text().trim()
                if (text.isBlank()) continue
                if (isOmeleteNoise(text)) continue

                if (element.tagName() in listOf("h2", "h3")) {
                    paragraphs.add("\n$text\n")
                } else {
                    paragraphs.add(text)
                }
            }
        }

        val content = paragraphs.joinToString("\n\n").trim()

        insertArticle(url, title, summary, content, imageUrl, "Omelete", publishedAt, now)
    }

    private fun isOmeleteNoise(text: String): Boolean {
        val noiseTexts = listOf(
            "Omelete Recomenda",
            "Webstories",
            "Comentários",
            "Os comentários são moderados",
            "Faça login para comentar",
            "Escrever comentário",
            "Editar comentário",
            "Excluir comentário",
            "Confirmar a exclusão",
        )
        val noisePatterns = listOf(
            Regex("^Tudo sobre .*"),
            Regex("^Comentários \\(\\d+\\)"),
        )
        return noiseTexts.any { text.startsWith(it) } || noisePatterns.any { it.matches(text) }
    }

    private fun extractJsonLd(doc: Document): JsonObject? {
        val scripts = doc.select("script[type=application/ld+json]")
        for (script in scripts) {
            try {
                val parsed = json.parseToJsonElement(script.data())
                val graph = parsed.jsonObject["@graph"]?.jsonArray ?: continue
                val newsArticle = graph.firstOrNull { element ->
                    element.jsonObject["@type"]?.jsonPrimitive?.content == "NewsArticle"
                }
                if (newsArticle != null) return newsArticle.jsonObject
            } catch (_: Exception) {
            }
        }
        return null
    }

    // endregion

    // region Shared helpers

    private fun fetchPage(url: String): Document {
        return Jsoup.connect(url)
            .userAgent(USER_AGENT)
            .timeout(15_000)
            .get()
    }

    private fun articleExists(sourceUrl: String): Boolean {
        return transaction {
            ArticlesTable.selectAll()
                .where { ArticlesTable.sourceUrl eq sourceUrl }
                .count() > 0
        }
    }

    private fun insertArticle(
        sourceUrl: String,
        title: String,
        summary: String,
        content: String,
        imageUrl: String?,
        source: String,
        publishedAt: Instant,
        scrapedAt: Instant
    ) {
        transaction {
            ArticlesTable.insertIgnore {
                it[ArticlesTable.sourceUrl] = sourceUrl
                it[ArticlesTable.title] = title
                it[ArticlesTable.summary] = summary
                it[ArticlesTable.content] = content
                it[ArticlesTable.imageUrl] = imageUrl?.takeIf { url -> url.isNotBlank() }
                it[sourceName] = source
                it[ArticlesTable.publishedAt] = publishedAt
                it[ArticlesTable.scrapedAt] = scrapedAt
            }
        }
    }

    private fun parseRssDate(dateStr: String?): Instant? {
        if (dateStr.isNullOrBlank()) return null
        return try {
            val formatter = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH)
            val zdt = ZonedDateTime.parse(dateStr, formatter)
            Instant.fromEpochSeconds(zdt.toEpochSecond())
        } catch (e: Exception) {
            log.warn("Failed to parse RSS date: $dateStr", e)
            null
        }
    }

    private fun parseIsoDate(dateStr: String): Instant? {
        return try {
            val zdt = ZonedDateTime.parse(dateStr, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
            Instant.fromEpochSeconds(zdt.toEpochSecond())
        } catch (e: Exception) {
            log.warn("Failed to parse ISO date: $dateStr", e)
            null
        }
    }

    private fun org.jetbrains.exposed.sql.ResultRow.toArticle(): Article {
        return Article(
            id = this[ArticlesTable.id].value,
            title = this[ArticlesTable.title],
            summary = this[ArticlesTable.summary],
            content = this[ArticlesTable.content],
            imageUrl = this[ArticlesTable.imageUrl],
            sourceUrl = this[ArticlesTable.sourceUrl],
            source = this[ArticlesTable.sourceName],
            publishedAt = this[ArticlesTable.publishedAt].toString()
        )
    }

    // endregion
}
