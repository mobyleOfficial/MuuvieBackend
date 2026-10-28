package org.mobyle.routing

import io.ktor.client.request.get
import io.ktor.client.request.header
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.mobyle.configureStatusPages
import org.mobyle.domain.model.JWTClaims
import org.mobyle.domain.model.MovieCard
import org.mobyle.domain.model.Page
import org.mobyle.domain.model.PageRequest
import org.mobyle.domain.model.ProfileDraft
import org.mobyle.domain.model.ProfileHighlight
import org.mobyle.domain.model.ProfileRecord
import org.mobyle.domain.model.ProfileStats
import org.mobyle.domain.model.Shelf
import org.mobyle.domain.model.vocabulary.HighlightType
import org.mobyle.domain.model.vocabulary.ShelfSort
import org.mobyle.domain.model.vocabulary.Visibility
import org.mobyle.domain.repository.AuthRepository
import org.mobyle.domain.repository.LocadoraRepository
import org.mobyle.domain.repository.ShelfRepository
import org.mobyle.domain.repository.UserProfileRepository
import org.mobyle.domain.repository.WatchRepository
import org.mobyle.domain.usecase.auth.ValidateToken
import org.mobyle.domain.usecase.locadoras.LocadoraAccess
import org.mobyle.domain.usecase.shelves.ShelfAccess
import org.mobyle.domain.usecase.users.CheckHandleAvailability
import org.mobyle.domain.usecase.users.GetMyProfile
import org.mobyle.domain.usecase.users.GetProfile
import org.mobyle.domain.usecase.users.GetProfileHighlights
import org.mobyle.domain.usecase.users.GetProfileStats
import org.mobyle.domain.usecase.users.ProfileAccess
import org.mobyle.domain.usecase.users.SaveProfileHighlights
import org.mobyle.domain.usecase.users.UpdateMyProfile
import org.mobyle.domain.usecase.watch.GetUserDiary
import org.mobyle.domain.usecase.feed.ActivityPublisher
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class UsersRoutingTest {

    private val repository = mockk<UserProfileRepository>(relaxed = true)
    private val shelfRepository = mockk<ShelfRepository>(relaxed = true)
    private val locadoraRepository = mockk<LocadoraRepository>(relaxed = true)
    private val watchRepository = mockk<WatchRepository>(relaxed = true)
    private val activities = mockk<ActivityPublisher>(relaxed = true)
    private val authRepository = mockk<AuthRepository>()

    private fun record(id: String, handle: String?) = ProfileRecord(
        id = id, username = id, handle = handle, displayName = null, pronouns = null, bio = null, avatarUrl = null,
        bannerUrl = null, accentColor = null, links = emptyList(), featured = false
    )

    private fun shelf(id: Long, owner: String, visibility: Visibility = Visibility.PUBLIC) = Shelf(
        id = id, ownerId = owner, name = "Shelf $id", description = null, color = null, visibility = visibility,
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
        every { repository.findUserIdByHandle(any()) } returns null
        every { repository.getProfile(any()) } returns null
        every { repository.getProfile("ana") } returns record("ana", "ana.cinefila")
        every { repository.getProfile("bia") } returns record("bia", null)
        every { repository.findUserIdByHandle("ana.cinefila") } returns "ana"
        every { repository.stats(any(), any()) } returns ProfileStats(1, 0, 0, 0, followers = 0, following = 0)
        every { shelfRepository.userExists(any()) } returns true
        every { shelfRepository.getShelf(10) } returns shelf(10, "ana")
        every { shelfRepository.getShelf(11) } returns shelf(11, "ana", Visibility.SECRET)
        every { shelfRepository.getShelf(20) } returns shelf(20, "bia")

        startKoin {
            modules(module {
                single { ValidateToken(authRepository) }
                single { ShelfAccess(shelfRepository) }
                single { LocadoraAccess(locadoraRepository, get()) }
                single { ProfileAccess(repository, get()) }
                single { GetMyProfile(repository, get()) }
                single { GetProfile(get()) }
                single { GetProfileStats(get()) }
                single { UpdateMyProfile(repository, get()) }
                single { CheckHandleAvailability(repository) }
                single { GetProfileHighlights(repository, get(), get(), get()) }
                single { SaveProfileHighlights(repository, get(), get(), get(), activities) }
                single { GetUserDiary(watchRepository, shelfRepository, get()) }
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
                route(API_V1_PREFIX) { getUsersRouting() }
            }
        }
        block()
    }

    private fun body(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `a profile opens by @handle and carries the relation to the viewer`() = withApp {
        every { repository.isFollowing("bia", "ana") } returns true
        every { repository.isFollowing("ana", "bia") } returns true

        val response = client.get("/api/v1/users/@Ana.Cinefila") { header("Authorization", "Bearer bia-token") }

        assertEquals(HttpStatusCode.OK, response.status)
        val json = body(response.bodyAsText())
        assertEquals("ana", json["id"]!!.jsonPrimitive.content)
        assertEquals("true", json["mutual"]!!.jsonPrimitive.content)
        assertEquals("false", json["self"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an unknown handle is 404`() = withApp {
        val response = client.get("/api/v1/users/@ninguem.aqui")

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `saving a handle someone else uses is 409`() = withApp {
        every { repository.isHandleTaken("ana.cinefila", "bia") } returns true

        val response = client.put("/api/v1/users/me") {
            header("Authorization", "Bearer bia-token")
            contentType(ContentType.Application.Json)
            setBody("""{"handle":"@ana.cinefila"}""")
        }

        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals("handle_taken", body(response.bodyAsText())["error"]!!.jsonPrimitive.content)
        verify(exactly = 0) { repository.updateProfile(any(), any()) }
    }

    @Test
    fun `an invalid handle or accent color is 400 with the field`() = withApp {
        for ((payload, field) in listOf("""{"handle":"ab"}""" to "handle", """{"accentColor":"red"}""" to "accentColor")) {
            val response = client.put("/api/v1/users/me") {
                header("Authorization", "Bearer bia-token")
                contentType(ContentType.Application.Json)
                setBody(payload)
            }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(field, body(response.bodyAsText())["details"]!!.jsonObject["field"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `saving the profile trims and normalizes before writing`() = withApp {
        val response = client.put("/api/v1/users/me") {
            header("Authorization", "Bearer bia-token")
            contentType(ContentType.Application.Json)
            setBody("""{"displayName":"  Bia ","handle":"Bia_Filmes","links":[" https://bia.example ",""],"accentColor":"#ff5500"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        verify {
            repository.updateProfile(
                "bia",
                ProfileDraft("Bia", "bia_filmes", null, null, listOf("https://bia.example"), "#FF5500", null, null)
            )
        }
    }

    @Test
    fun `handle availability says why a handle cannot be used`() = withApp {
        every { repository.isHandleTaken("ana.cinefila", null) } returns true

        assertEquals("taken", body(client.get("/api/v1/handles/ana.cinefila/available").bodyAsText())["reason"]!!.jsonPrimitive.content)
        assertEquals("invalid", body(client.get("/api/v1/handles/ab/available").bodyAsText())["reason"]!!.jsonPrimitive.content)
        assertEquals("true", body(client.get("/api/v1/handles/livre.aqui/available").bodyAsText())["available"]!!.jsonPrimitive.content)
    }

    @Test
    fun `others only see enabled highlights they can open, the owner sees all`() = withApp {
        every { repository.getHighlights("ana") } returns listOf(
            ProfileHighlight(HighlightType.TOP4, 0, enabled = true, movieIds = listOf(10)),
            ProfileHighlight(HighlightType.SHELF, 1, enabled = true, shelfId = 11),
            ProfileHighlight(HighlightType.SOUNDTRACK, 2, enabled = false, soundtrackUrl = "https://open.spotify.com/x")
        )
        every { repository.movieCards(listOf(10)) } returns listOf(MovieCard(10, 100, "Halloween", null, 1978))

        val forOthers = client.get("/api/v1/users/ana/highlights") { header("Authorization", "Bearer bia-token") }
        val types = Json.parseToJsonElement(forOthers.bodyAsText()).jsonArray.map { (it as JsonObject)["type"]!!.jsonPrimitive.content }
        assertEquals(listOf("top4"), types)

        val forOwner = client.get("/api/v1/users/me/highlights") { header("Authorization", "Bearer ana-token") }
        assertEquals(3, Json.parseToJsonElement(forOwner.bodyAsText()).jsonArray.size)
    }

    @Test
    fun `a featured shelf of someone else is 403 and two highlights of a type are 400`() = withApp {
        val foreign = client.put("/api/v1/users/me/highlights") {
            header("Authorization", "Bearer ana-token")
            contentType(ContentType.Application.Json)
            setBody("""{"highlights":[{"type":"shelf","shelfId":20}]}""")
        }
        assertEquals(HttpStatusCode.Forbidden, foreign.status)

        val repeated = client.put("/api/v1/users/me/highlights") {
            header("Authorization", "Bearer ana-token")
            contentType(ContentType.Application.Json)
            setBody("""{"highlights":[{"type":"shelf","shelfId":10},{"type":"shelf","shelfId":10}]}""")
        }
        assertEquals(HttpStatusCode.BadRequest, repeated.status)
        verify(exactly = 0) { repository.saveHighlights(any(), any()) }
    }

    @Test
    fun `a soundtrack outside Spotify and YouTube Music is 400`() = withApp {
        val response = client.put("/api/v1/users/me/highlights") {
            header("Authorization", "Bearer ana-token")
            contentType(ContentType.Application.Json)
            setBody("""{"highlights":[{"type":"soundtrack","soundtrackUrl":"https://example.com/song"}]}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("soundtrackUrl", body(response.bodyAsText())["details"]!!.jsonObject["field"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the diary of another user only asks for the public sessions`() = withApp {
        every { watchRepository.getUserLogs(any(), any(), any()) } answers {
            Page.of(emptyList(), thirdArg<PageRequest>(), totalResults = 0)
        }

        client.get("/api/v1/users/ana/logs") { header("Authorization", "Bearer bia-token") }

        verify { watchRepository.getUserLogs("ana", setOf(Visibility.PUBLIC), any()) }
    }
}
