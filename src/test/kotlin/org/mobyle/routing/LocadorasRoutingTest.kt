package org.mobyle.routing

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.mobyle.configureStatusPages
import org.mobyle.domain.model.JWTClaims
import org.mobyle.domain.model.Locadora
import org.mobyle.domain.model.LocadoraDraft
import org.mobyle.domain.model.Page
import org.mobyle.domain.model.PageRequest
import org.mobyle.domain.model.Shelf
import org.mobyle.domain.model.vocabulary.ShelfSort
import org.mobyle.domain.model.vocabulary.Visibility
import org.mobyle.domain.repository.AuthRepository
import org.mobyle.domain.repository.LocadoraRepository
import org.mobyle.domain.repository.ShelfRepository
import org.mobyle.domain.usecase.auth.ValidateToken
import org.mobyle.domain.usecase.locadoras.CreateLocadora
import org.mobyle.domain.usecase.locadoras.DeleteLocadora
import org.mobyle.domain.usecase.locadoras.GetLocadora
import org.mobyle.domain.usecase.locadoras.GetLocadoraShelves
import org.mobyle.domain.usecase.locadoras.GetUserLocadoras
import org.mobyle.domain.usecase.locadoras.LocadoraAccess
import org.mobyle.domain.usecase.locadoras.ReorderLocadoraShelves
import org.mobyle.domain.usecase.locadoras.ReorderLocadoras
import org.mobyle.domain.usecase.locadoras.SetLocadoraShelves
import org.mobyle.domain.usecase.locadoras.UpdateLocadora
import org.mobyle.domain.usecase.shelves.ShelfAccess
import org.mobyle.domain.usecase.feed.ActivityPublisher
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class LocadorasRoutingTest {

    private val repository = mockk<LocadoraRepository>(relaxed = true)
    private val shelfRepository = mockk<ShelfRepository>(relaxed = true)
    private val activities = mockk<ActivityPublisher>(relaxed = true)
    private val authRepository = mockk<AuthRepository>()

    private fun locadora(id: Long, visibility: Visibility = Visibility.PUBLIC) = Locadora(
        id = id, ownerId = "ana", ownerUsername = "ana", ownerAvatarUrl = null, name = "Locadora $id",
        description = null, visibility = visibility, hideShelvesInProfile = false, hideShelvesInFilters = false,
        position = 0, pinned = false, coverUrl = null, totalShelves = 0, totalMovies = 0, totalWatched = 0,
        posterPaths = emptyList(), createdAt = Instant.fromEpochSeconds(0), updatedAt = Instant.fromEpochSeconds(0)
    )

    private fun shelf(id: Long, owner: String) = Shelf(
        id = id, ownerId = owner, name = "Shelf $id", description = null, color = null, visibility = Visibility.PUBLIC,
        systemType = null, sortMode = ShelfSort.MANUAL, position = 0, pinned = false, coverUrl = null,
        totalMovies = 0, totalWatched = 0, posterPaths = emptyList(),
        createdAt = Instant.fromEpochSeconds(0), updatedAt = Instant.fromEpochSeconds(0)
    )

    @BeforeTest
    fun setUp() {
        coEvery { authRepository.validateToken("ana-token") } returns
            Result.success(JWTClaims("ana", "ana@example.com", "ana", null, 0, Long.MAX_VALUE))
        coEvery { authRepository.validateToken("bia-token") } returns
            Result.success(JWTClaims("bia", "bia@example.com", "bia", null, 0, Long.MAX_VALUE))
        every { repository.getLocadoraOwnerId(1) } returns "ana"
        every { repository.getLocadora(1, any()) } returns locadora(1)
        every { repository.getLocadoraOwnerId(2) } returns "ana"
        every { repository.getLocadora(2, any()) } returns locadora(2, Visibility.SECRET)
        every { repository.locadoraShelfIds(1) } returns listOf(10, 11)
        every { repository.createLocadora("ana", any()) } answers { locadora(3).copy(name = secondArg<LocadoraDraft>().name) }
        every { repository.getLocadoraShelves(any(), any(), any(), any()) } answers {
            Page.of(emptyList(), thirdArg<PageRequest>(), totalResults = 0)
        }
        every { shelfRepository.getShelf(10) } returns shelf(10, "ana")
        every { shelfRepository.getShelf(20) } returns shelf(20, "bia")

        startKoin {
            modules(module {
                single { ValidateToken(authRepository) }
                single { ShelfAccess(shelfRepository) }
                single { LocadoraAccess(repository, get()) }
                single { GetUserLocadoras(repository, shelfRepository, get()) }
                single { GetLocadora(get()) }
                single { CreateLocadora(repository, activities) }
                single { UpdateLocadora(repository, get(), activities) }
                single { DeleteLocadora(repository, get(), activities) }
                single { GetLocadoraShelves(repository, get(), get()) }
                single { SetLocadoraShelves(repository, get()) }
                single { ReorderLocadoraShelves(repository, get()) }
                single { ReorderLocadoras(repository, get()) }
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
                route(API_V1_PREFIX) { getLocadorasRouting() }
            }
        }
        block()
    }

    private fun body(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `creating trims the name and answers 201 with self`() = withApp {
        val response = client.post("/api/v1/locadoras") {
            header("Authorization", "Bearer ana-token")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"  Locadora do Terror ","hideShelvesInProfile":true}""")
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val json = body(response.bodyAsText())
        assertEquals("Locadora do Terror", json["name"]!!.jsonPrimitive.content)
        assertEquals("true", json["self"]!!.jsonPrimitive.content)
        verify {
            repository.createLocadora("ana", LocadoraDraft("Locadora do Terror", null, Visibility.PUBLIC, true, false))
        }
    }

    @Test
    fun `a name over 150 characters is 400`() = withApp {
        val response = client.post("/api/v1/locadoras") {
            header("Authorization", "Bearer ana-token")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"${"a".repeat(151)}"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("name", body(response.bodyAsText())["details"]!!.jsonObject["field"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a secret locadora is 404 for anyone else`() = withApp {
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/locadoras/2").status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/locadoras/2") { header("Authorization", "Bearer ana-token") }.status)
    }

    @Test
    fun `only the owner changes a locadora`() = withApp {
        val response = client.delete("/api/v1/locadoras/1") { header("Authorization", "Bearer bia-token") }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertEquals("not_locadora_owner", body(response.bodyAsText())["error"]!!.jsonPrimitive.content)
        verify(exactly = 0) { repository.deleteLocadora(any()) }
    }

    @Test
    fun `a shelf of someone else cannot go in, mine can`() = withApp {
        val others = client.put("/api/v1/locadoras/1/shelves") {
            header("Authorization", "Bearer ana-token")
            contentType(ContentType.Application.Json)
            setBody("""{"shelfIds":[10,20]}""")
        }
        assertEquals(HttpStatusCode.Forbidden, others.status)
        verify(exactly = 0) { repository.setShelves(any(), any()) }

        val mine = client.put("/api/v1/locadoras/1/shelves") {
            header("Authorization", "Bearer ana-token")
            contentType(ContentType.Application.Json)
            setBody("""{"shelfIds":[10,10]}""")
        }
        assertEquals(HttpStatusCode.NoContent, mine.status)
        verify { repository.setShelves(1, listOf(10)) }
    }

    @Test
    fun `reordering only takes shelves already in the locadora`() = withApp {
        val outside = client.put("/api/v1/locadoras/1/shelves/order") {
            header("Authorization", "Bearer ana-token")
            contentType(ContentType.Application.Json)
            setBody("""{"shelfIds":[11,99]}""")
        }
        assertEquals(HttpStatusCode.BadRequest, outside.status)

        val inside = client.put("/api/v1/locadoras/1/shelves/order") {
            header("Authorization", "Bearer ana-token")
            contentType(ContentType.Application.Json)
            setBody("""{"shelfIds":[11,10]}""")
        }
        assertEquals(HttpStatusCode.NoContent, inside.status)
        verify { repository.reorderShelves(1, listOf(11, 10)) }
    }

    @Test
    fun `the shelves inside follow the viewer relation`() = withApp {
        client.get("/api/v1/locadoras/1/shelves")
        client.get("/api/v1/locadoras/1/shelves") { header("Authorization", "Bearer ana-token") }

        verify { repository.getLocadoraShelves(1, setOf(Visibility.PUBLIC), any(), 10) }
        verify { repository.getLocadoraShelves(1, Visibility.entries.toSet(), any(), 10) }
    }
}
