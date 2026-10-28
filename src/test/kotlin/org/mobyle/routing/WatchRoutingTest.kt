package org.mobyle.routing

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.serialization.kotlinx.json.json
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.mobyle.configureStatusPages
import org.mobyle.data.repository.GifRepositoryImpl
import org.mobyle.domain.model.CineClubeFilter
import org.mobyle.domain.model.EmotionCount
import org.mobyle.domain.model.JWTClaims
import org.mobyle.domain.model.MaratonaFilter
import org.mobyle.domain.model.MovieActivity
import org.mobyle.domain.model.MovieCommunity
import org.mobyle.domain.model.MovieRating
import org.mobyle.domain.model.Page
import org.mobyle.domain.model.PageRequest
import org.mobyle.domain.model.WatchLog
import org.mobyle.domain.model.vocabulary.Emotion
import org.mobyle.domain.model.vocabulary.Rating
import org.mobyle.domain.model.vocabulary.Visibility
import org.mobyle.domain.model.vocabulary.WatchMode
import org.mobyle.domain.repository.AuthRepository
import org.mobyle.domain.repository.CineClubeRepository
import org.mobyle.domain.repository.CommentThreadRepository
import org.mobyle.domain.repository.MaratonaRepository
import org.mobyle.domain.repository.ShelfRepository
import org.mobyle.domain.repository.WatchRepository
import org.mobyle.domain.repository.WatchableMovie
import org.mobyle.domain.usecase.auth.ValidateToken
import org.mobyle.domain.usecase.comments.CommentAccess
import org.mobyle.domain.usecase.comments.DeleteComment
import org.mobyle.domain.usecase.comments.GetThreadComments
import org.mobyle.domain.usecase.comments.PostComment
import org.mobyle.domain.usecase.movies.GetMovieCineClubes
import org.mobyle.domain.usecase.movies.GetMovieMaratonas
import org.mobyle.domain.usecase.movies.GetMovieTimeline
import org.mobyle.domain.usecase.shelves.ShelfAccess
import org.mobyle.domain.usecase.watch.DeleteWatchLog
import org.mobyle.domain.usecase.watch.GetMovieCommunity
import org.mobyle.domain.usecase.watch.GetMovieLogs
import org.mobyle.domain.usecase.watch.GetMovieUserState
import org.mobyle.domain.usecase.watch.GetRecentEmotions
import org.mobyle.domain.usecase.watch.GetRecentGifs
import org.mobyle.domain.usecase.watch.GetWatchingNow
import org.mobyle.domain.usecase.watch.LogWatchSession
import org.mobyle.domain.usecase.watch.RemoveMovieRating
import org.mobyle.domain.usecase.watch.SearchGifs
import org.mobyle.domain.usecase.watch.SetMovieRating
import org.mobyle.domain.usecase.watch.SetWatchingNow
import org.mobyle.domain.usecase.watch.StartRewatch
import org.mobyle.domain.usecase.watch.UpdateWatchLog
import org.mobyle.domain.usecase.watch.WatchLogAccess
import org.mobyle.domain.usecase.feed.ActivityPublisher
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WatchRoutingTest {

    private val repository = mockk<WatchRepository>(relaxed = true)
    private val shelves = mockk<ShelfRepository>(relaxed = true)
    private val comments = mockk<CommentThreadRepository>(relaxed = true)
    private val activities = mockk<ActivityPublisher>(relaxed = true)
    private val cineclubes = mockk<CineClubeRepository>()
    private val maratonas = mockk<MaratonaRepository>()
    private val authRepository = mockk<AuthRepository>()

    private fun log(visibility: Visibility) = WatchLog(
        id = 5, userId = "bia", movieId = 10, watchedOn = LocalDate(2026, 10, 1), cycle = 1, mode = WatchMode.WATCHED,
        minutesWatched = 100, percent = 100, completed = true, venue = null, providerName = null,
        emotions = emptyList(), gif = null, comment = null, hasSpoiler = false, visibility = visibility,
        createdAt = Instant.fromEpochSeconds(0), updatedAt = Instant.fromEpochSeconds(0)
    )

    @BeforeTest
    fun setUp() {
        coEvery { authRepository.validateToken("ana-token") } returns
            Result.success(JWTClaims("ana", "ana@example.com", "ana", null, 0, Long.MAX_VALUE))
        every { shelves.resolveMovieId(10, null, null) } returns 10
        every { repository.getMovie(10) } returns WatchableMovie(10, 100, "Casablanca", null, 100)
        every { repository.getLog(5) } returns log(Visibility.SECRET)
        every { repository.createLog("ana", 10, any()) } returns log(Visibility.PUBLIC).copy(userId = "ana")

        startKoin {
            modules(module {
                single { ValidateToken(authRepository) }
                single { ShelfAccess(shelves) }
                single { WatchLogAccess(repository, get()) }
                single { LogWatchSession(repository, shelves, activities) }
                single { GetMovieLogs(repository, shelves, get()) }
                single { UpdateWatchLog(repository, get(), activities) }
                single { DeleteWatchLog(repository, get(), activities) }
                single { StartRewatch(repository) }
                single { SetMovieRating(repository, activities) }
                single { RemoveMovieRating(repository, activities) }
                single { GetMovieUserState(repository, shelves) }
                single { SetWatchingNow(repository, activities) }
                single { GetWatchingNow(repository, shelves) }
                single { GetRecentEmotions(repository) }
                single { GetRecentGifs(repository) }
                single { GetMovieCommunity(repository) }
                single { GetMovieCineClubes(cineclubes) { LocalDate(2026, 10, 27) } }
                single { GetMovieMaratonas(maratonas) }
                single { GetMovieTimeline(repository) { LocalDate(2026, 10, 27) } }
                single { SearchGifs(GifRepositoryImpl(null)) }
                single { CommentAccess(comments, get()) }
                single { GetThreadComments(comments, get()) }
                single { PostComment(comments, get()) }
                single { DeleteComment(comments, get()) }
            })
        }
    }

    @AfterTest
    fun tearDown() {
        stopKoin()
    }

    private fun withApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            configureStatusPages()
            install(ContentNegotiation) { json() }
            routing {
                route(API_V1_PREFIX) {
                    getWatchRouting()
                    getCommentsRouting()
                }
            }
        }
        block()
    }

    @Test
    fun `one-tap Ja assisti only needs an empty body`() = withApp {
        val response = client.post("/api/v1/movies/10/logs") {
            header("Authorization", "Bearer ana-token")
            contentType(ContentType.Application.Json)
            setBody("{}")
        }

        assertEquals(HttpStatusCode.Created, response.status)
        verify { repository.createLog("ana", 10, match { it.completed && it.mode == WatchMode.WATCHED }) }
    }

    @Test
    fun `minutes above the runtime answer 400 with the field`() = withApp {
        val response = client.post("/api/v1/movies/10/logs") {
            header("Authorization", "Bearer ana-token")
            contentType(ContentType.Application.Json)
            setBody("""{"mode":"minutes","minutesWatched":101}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("validation_failed", body["error"]!!.jsonPrimitive.content)
        assertEquals("minutesWatched", body["details"]!!.jsonObject["field"]!!.jsonPrimitive.content)
    }

    @Test
    fun `rating answers with the label`() = withApp {
        every { repository.setRating("ana", 10, Rating.LIKED, null, false) } returns
            MovieRating(1, Rating.LIKED, null, false, null)

        val response = client.put("/api/v1/movies/10/rating") {
            header("Authorization", "Bearer ana-token")
            contentType(ContentType.Application.Json)
            setBody("""{"rating":"liked"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("liked", Json.parseToJsonElement(response.bodyAsText()).jsonObject["rating"]!!.jsonPrimitive.content)
    }

    @Test
    fun `comments of someone else's secret log are 404`() = withApp {
        val read = client.get("/api/v1/comments?targetType=watch_log&targetId=5") {
            header("Authorization", "Bearer ana-token")
        }
        val write = client.post("/api/v1/comments") {
            header("Authorization", "Bearer ana-token")
            contentType(ContentType.Application.Json)
            setBody("""{"targetType":"watch_log","targetId":5,"body":"Oi"}""")
        }

        assertEquals(HttpStatusCode.NotFound, read.status)
        assertEquals(HttpStatusCode.NotFound, write.status)
    }

    @Test
    fun `gif search without a provider key is 503`() = withApp {
        val response = client.get("/api/v1/gifs/search?q=cinema") { header("Authorization", "Bearer ana-token") }

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals(
            "gif_search_unavailable",
            Json.parseToJsonElement(response.bodyAsText()).jsonObject["error"]!!.jsonPrimitive.content
        )
    }

    @Test
    fun `logs of me need a token`() = withApp {
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/movies/10/logs").status)
    }

    @Test
    fun `the community answers the full emotion distribution as emotions`() = withApp {
        every { repository.community(10) } returns MovieCommunity(
            watched = 2,
            emotions = listOf(EmotionCount(Emotion.JOY, 2), EmotionCount(Emotion.FEAR, 1))
        )

        val response = client.get("/api/v1/movies/10/community")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(2, body["emotions"]!!.jsonArray.size)
        assertNull(body["topEmotions"])
    }

    @Test
    fun `the CineClubes and maratonas of a movie are pages, with joined for a token`() = withApp {
        every { cineclubes.list(any(), any(), any(), any()) } answers { Page.of(emptyList(), thirdArg(), 0) }
        every { maratonas.list(any(), any(), any()) } answers { Page.of(emptyList(), thirdArg(), 0) }

        val cineclubePage = client.get("/api/v1/movies/10/cineclubes?page=2&limit=5") { header("Authorization", "Bearer ana-token") }
        val maratonaPage = client.get("/api/v1/movies/10/maratonas")

        assertEquals(HttpStatusCode.OK, cineclubePage.status)
        assertEquals(2, Json.parseToJsonElement(cineclubePage.bodyAsText()).jsonObject["page"]!!.jsonPrimitive.int)
        assertEquals(HttpStatusCode.OK, maratonaPage.status)
        assertEquals(0, Json.parseToJsonElement(maratonaPage.bodyAsText()).jsonObject["results"]!!.jsonArray.size)
        verify { cineclubes.list("ana", CineClubeFilter(movieId = 10, includeFinished = true), PageRequest(2, 5), any()) }
        verify { maratonas.list(null, MaratonaFilter(movieId = 10, includeFinished = true), PageRequest()) }
    }

    @Test
    fun `bad pagination or movie id on the movie lists is a 400`() = withApp {
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/movies/10/cineclubes?limit=500").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/movies/abc/maratonas").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/movies/10/maratonas?page=0").status)
    }

    @Test
    fun `the timeline answers one row per month`() = withApp {
        every { repository.activity(10, any(), any()) } returns MovieActivity()

        val response = client.get("/api/v1/movies/10/timeline?months=3")

        assertEquals(HttpStatusCode.OK, response.status)
        val months = Json.parseToJsonElement(response.bodyAsText()).jsonObject["months"]!!.jsonArray
        assertEquals(3, months.size)
        assertEquals(setOf("month", "sessions", "ratings"), months.first().jsonObject.keys)
        assertEquals(12, Json.parseToJsonElement(client.get("/api/v1/movies/10/timeline").bodyAsText()).jsonObject["months"]!!.jsonArray.size)
    }

    @Test
    fun `a timeline window outside 1 to 24 months or not a number is a 400`() = withApp {
        listOf("0", "25", "abc").forEach { months ->
            val response = client.get("/api/v1/movies/10/timeline?months=$months")
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("months", Json.parseToJsonElement(response.bodyAsText()).jsonObject["details"]!!.jsonObject["field"]!!.jsonPrimitive.content)
        }
    }
}
