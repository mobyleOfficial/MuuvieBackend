package org.mobyle.routing

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.mobyle.configureStatusPages
import org.mobyle.domain.model.JWTClaims
import org.mobyle.domain.model.Page
import org.mobyle.domain.model.PageRequest
import org.mobyle.domain.model.Shelf
import org.mobyle.domain.model.vocabulary.ShelfSort
import org.mobyle.domain.model.vocabulary.SystemShelfType
import org.mobyle.domain.model.vocabulary.Visibility
import org.mobyle.domain.repository.AuthRepository
import org.mobyle.domain.repository.ShelfRepository
import org.mobyle.domain.usecase.auth.ValidateToken
import org.mobyle.domain.usecase.shelves.AddMovieToShelves
import org.mobyle.domain.usecase.shelves.CreateShelf
import org.mobyle.domain.usecase.shelves.DeleteShelf
import org.mobyle.domain.usecase.shelves.GetMovieShelves
import org.mobyle.domain.usecase.shelves.GetSavedShelves
import org.mobyle.domain.usecase.shelves.GetShelf
import org.mobyle.domain.usecase.shelves.GetShelfMovies
import org.mobyle.domain.usecase.shelves.GetUserShelves
import org.mobyle.domain.usecase.shelves.RemoveMovieFromShelf
import org.mobyle.domain.usecase.shelves.ReorderShelves
import org.mobyle.domain.usecase.shelves.SaveShelf
import org.mobyle.domain.usecase.shelves.SearchShelves
import org.mobyle.domain.usecase.shelves.ShelfAccess
import org.mobyle.domain.usecase.shelves.SortShelf
import org.mobyle.domain.usecase.shelves.TransferShelfMovies
import org.mobyle.domain.usecase.shelves.UnsaveShelf
import org.mobyle.domain.usecase.shelves.UpdateShelf
import org.mobyle.domain.usecase.feed.ActivityPublisher
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ShelvesRoutingTest {

    private val repository = mockk<ShelfRepository>(relaxed = true)
    private val activities = mockk<ActivityPublisher>(relaxed = true)
    private val authRepository = mockk<AuthRepository>()

    private fun shelf(id: Long, visibility: Visibility = Visibility.PUBLIC, systemType: SystemShelfType? = null) = Shelf(
        id = id, ownerId = "ana", name = "Shelf $id", description = null, color = null, visibility = visibility,
        systemType = systemType, sortMode = ShelfSort.MANUAL, position = 0, pinned = false, coverUrl = null,
        totalMovies = 0, totalWatched = 0, posterPaths = emptyList(),
        createdAt = Instant.fromEpochSeconds(0), updatedAt = Instant.fromEpochSeconds(0)
    )

    @BeforeTest
    fun setUp() {
        coEvery { authRepository.validateToken("ana-token") } returns
            Result.success(JWTClaims("ana", "ana@example.com", "ana", null, 0, Long.MAX_VALUE))
        coEvery { authRepository.validateToken("bia-token") } returns
            Result.success(JWTClaims("bia", "bia@example.com", "bia", null, 0, Long.MAX_VALUE))
        every { repository.getShelf(3) } returns shelf(3)
        every { repository.isSaved(3, "bia") } returns true
        every { repository.savedShelves("bia", any()) } answers {
            Page.of(listOf(shelf(3).copy(saved = true)), secondArg<PageRequest>(), totalResults = 1)
        }
        every { repository.getShelf(1) } returns shelf(1, systemType = SystemShelfType.WATCHED)
        every { repository.getShelf(2) } returns shelf(2, visibility = Visibility.SECRET)
        every { repository.userExists("ana") } returns true
        every { repository.getUserShelves("ana", any(), any()) } answers {
            Page.of(listOf(shelf(3)), thirdArg<PageRequest>(), totalResults = 1)
        }

        startKoin {
            modules(module {
                single { ValidateToken(authRepository) }
                single { ShelfAccess(repository) }
                single { GetUserShelves(repository, get()) }
                single { SearchShelves(repository) }
                single { GetShelf(get(), repository) }
                single { CreateShelf(repository, activities) }
                single { UpdateShelf(repository, get(), activities) }
                single { DeleteShelf(repository, get(), activities) }
                single { GetShelfMovies(repository, get()) }
                single { AddMovieToShelves(repository, get()) }
                single { RemoveMovieFromShelf(repository, get()) }
                single { TransferShelfMovies(repository, get()) }
                single { SortShelf(repository, get()) }
                single { ReorderShelves(repository, get()) }
                single { GetMovieShelves(repository) }
                single { SaveShelf(repository, get()) }
                single { UnsaveShelf(repository, get()) }
                single { GetSavedShelves(repository) }
            })
        }
    }

    @AfterTest
    fun tearDown() = stopKoin()

    private fun withApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            install(ContentNegotiation) { json() }
            configureStatusPages()
            routing {
                route(API_V1_PREFIX) { getShelvesRouting() }
            }
        }
        block()
    }

    private fun body(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `my shelves come in the page envelope with self set`() = withApp {
        val response = client.get("/api/v1/users/me/shelves") { header("Authorization", "Bearer ana-token") }

        assertEquals(HttpStatusCode.OK, response.status)
        val json = body(response.bodyAsText())
        assertEquals(1, json["totalResults"]!!.jsonPrimitive.int)
        assertEquals("true", json["results"]!!.jsonArray.single().jsonObject["self"]!!.jsonPrimitive.content)
    }

    @Test
    fun `saving a shelf of someone else is 204 and idempotent, while mine or a system one is 400`() = withApp {
        repeat(2) {
            val response = client.post("/api/v1/shelves/3/save") { header("Authorization", "Bearer bia-token") }
            assertEquals(HttpStatusCode.NoContent, response.status)
        }
        verify(exactly = 2) { repository.saveShelf(3, "bia") }

        val mine = client.post("/api/v1/shelves/3/save") { header("Authorization", "Bearer ana-token") }
        assertEquals(HttpStatusCode.BadRequest, mine.status)
        val system = client.post("/api/v1/shelves/1/save") { header("Authorization", "Bearer bia-token") }
        assertEquals(HttpStatusCode.BadRequest, system.status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/shelves/3/save").status)
        verify(exactly = 2) { repository.saveShelf(any(), any()) }
    }

    @Test
    fun `unsaving is 204, and a shelf I cannot see is 404 either way`() = withApp {
        val response = client.delete("/api/v1/shelves/3/save") { header("Authorization", "Bearer bia-token") }
        assertEquals(HttpStatusCode.NoContent, response.status)
        verify { repository.unsaveShelf(3, "bia") }

        val hidden = client.post("/api/v1/shelves/2/save") { header("Authorization", "Bearer bia-token") }
        assertEquals(HttpStatusCode.NotFound, hidden.status)
        verify(exactly = 0) { repository.saveShelf(2, any()) }
    }

    @Test
    fun `only a logged-in viewer who is not the owner counts as a visit and learns if they saved it`() = withApp {
        val asBia = client.get("/api/v1/shelves/3") { header("Authorization", "Bearer bia-token") }
        client.get("/api/v1/shelves/3") { header("Authorization", "Bearer ana-token") }
        client.get("/api/v1/shelves/3")

        assertEquals("true", body(asBia.bodyAsText())["saved"]!!.jsonPrimitive.content)
        verify(exactly = 1) { repository.recordVisit(any(), any()) }
        verify { repository.recordVisit(3, "bia") }
    }

    @Test
    fun `the saved list needs a token and comes in the page envelope`() = withApp {
        val response = client.get("/api/v1/users/me/shelves/saved") { header("Authorization", "Bearer bia-token") }

        assertEquals(HttpStatusCode.OK, response.status)
        val shelf = body(response.bodyAsText())["results"]!!.jsonArray.single().jsonObject
        assertEquals("true", shelf["saved"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/users/me/shelves/saved").status)
    }

    @Test
    fun `me without a token is 401`() = withApp {
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/users/me/shelves").status)
    }

    @Test
    fun `deleting a system shelf is 403 with a stable code`() = withApp {
        val response = client.delete("/api/v1/shelves/1") { header("Authorization", "Bearer ana-token") }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertEquals("system_shelf_delete", body(response.bodyAsText())["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a secret shelf is 404 for anyone else`() = withApp {
        val response = client.get("/api/v1/shelves/2")

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("not_found", body(response.bodyAsText())["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun `invalid input is 400 with the field`() = withApp {
        val response = client.post("/api/v1/shelves") {
            header("Authorization", "Bearer ana-token")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"ok","visibility":"everyone"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val json = body(response.bodyAsText())
        assertEquals("validation_failed", json["error"]!!.jsonPrimitive.content)
        assertEquals("visibility", json["details"]!!.jsonObject["field"]!!.jsonPrimitive.content)
    }

    @Test
    fun `malformed body and bad filter are 400`() = withApp {
        val malformed = client.post("/api/v1/shelves") {
            header("Authorization", "Bearer ana-token")
            contentType(ContentType.Application.Json)
            setBody("""{"nome":1}""")
        }
        assertEquals(HttpStatusCode.BadRequest, malformed.status)

        val badFilter = client.get("/api/v1/shelves/1/movies?decade=1975")
        assertEquals(HttpStatusCode.BadRequest, badFilter.status)
    }

    @Test
    fun `unexpected errors do not leak their message`() = withApp {
        every { repository.getShelf(9) } throws IllegalStateException("SELECT * FROM secret_table")

        val response = client.get("/api/v1/shelves/9")

        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertEquals("internal_error", body(response.bodyAsText())["error"]!!.jsonPrimitive.content)
        assertEquals(false, response.bodyAsText().contains("secret_table"))
    }
}
