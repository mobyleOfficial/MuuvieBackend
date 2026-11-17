package org.mobyle.routing

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.just
import io.mockk.runs
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.mobyle.configureStatusPages
import org.mobyle.domain.model.JWTClaims
import org.mobyle.domain.model.Movie
import org.mobyle.domain.model.MovieListState
import org.mobyle.domain.model.vocabulary.WatchStatus
import org.mobyle.domain.repository.AuthRepository
import org.mobyle.domain.repository.MoviesRepository
import org.mobyle.domain.repository.WatchRepository
import org.mobyle.domain.usecase.auth.ValidateToken
import org.mobyle.domain.usecase.movies.AttachMovieStates
import org.mobyle.domain.usecase.movies.GetSimilarMovies
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MoviesSimilarRoutingTest {

    private val moviesRepository = mockk<MoviesRepository>().also {
        every { it.enrichAhead(any()) } just runs
    }
    private val watchRepository = mockk<WatchRepository>()
    private val authRepository = mockk<AuthRepository>()
    private val similarMovies = listOf(Movie(id = 2, tmdbId = 348, title = "Alien"), Movie(id = 3, tmdbId = 679, title = "Aliens"))

    @BeforeTest
    fun setUp() {
        coEvery { authRepository.validateToken("ana-token") } returns
            Result.success(JWTClaims("ana", "ana@example.com", "ana", null, 0, Long.MAX_VALUE))
        coEvery { moviesRepository.getSimilarMovies(1) } returns similarMovies
        coEvery { moviesRepository.getSimilarMovies(404) } returns null
        coEvery { moviesRepository.getMovieTraits(any()) } returns emptyMap()

        startKoin {
            modules(module {
                single { ValidateToken(authRepository) }
                single { GetSimilarMovies(moviesRepository) }
                single { AttachMovieStates(watchRepository) }
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
                route(API_V1_PREFIX) { getMoviesRouting() }
            }
        }
        block()
    }

    @Test
    fun `logged out answers the similar movies without viewer state`() = withApp {
        val response = client.get("/api/v1/movies/1/similar")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(1L, body["movieId"]!!.jsonPrimitive.long)
        val results = body["results"]!!.jsonArray
        assertEquals(listOf("Alien", "Aliens"), results.map { it.jsonObject["title"]!!.jsonPrimitive.content })
        assertNoViewerState(results[0])
        val affinities = body["affinities"]!!.jsonArray
        assertEquals(listOf(2L, 3L), affinities.map { it.jsonObject["movieId"]!!.jsonPrimitive.long })
        assertTrue(affinities.all { it.jsonObject["score"]!!.jsonPrimitive.content.toDouble() in 0.0..1.0 })
    }

    @Test
    fun `logged in marks the movies the viewer already has a state for`() = withApp {
        every { watchRepository.listStates("ana", listOf(2L, 3L)) } returns
            mapOf(2L to MovieListState(status = WatchStatus.WATCHED))

        val response = client.get("/api/v1/movies/1/similar") { header("Authorization", "Bearer ana-token") }

        val results = Json.parseToJsonElement(response.bodyAsText()).jsonObject["results"]!!.jsonArray
        assertEquals("watched", results[0].jsonObject["me"]!!.jsonObject["status"]!!.jsonPrimitive.content)
        assertNoViewerState(results[1])
    }

    // The test app keeps the default JSON, which writes absent fields as null.
    private fun assertNoViewerState(movie: JsonElement) {
        val viewerState = movie.jsonObject["me"]
        assertTrue(viewerState == null || viewerState is JsonNull)
    }

    @Test
    fun `an unknown movie answers 404`() = withApp {
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/movies/404/similar").status)
    }

    @Test
    fun `an invalid id answers 400`() = withApp {
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/movies/abc/similar").status)
    }
}
