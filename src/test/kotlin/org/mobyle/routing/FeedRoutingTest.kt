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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.mobyle.configureStatusPages
import org.mobyle.domain.model.ActivityLoves
import org.mobyle.domain.model.ActivityRecord
import org.mobyle.domain.model.Cursor
import org.mobyle.domain.model.FollowStatus
import org.mobyle.domain.model.JWTClaims
import org.mobyle.domain.model.ProfileRecord
import org.mobyle.domain.model.SocialEvent
import org.mobyle.domain.model.UserSummary
import org.mobyle.domain.model.vocabulary.ActivitySubject
import org.mobyle.domain.model.vocabulary.ActivityType
import org.mobyle.domain.model.vocabulary.FeedScope
import org.mobyle.domain.model.vocabulary.MovieActivityKind
import org.mobyle.domain.model.vocabulary.Visibility
import org.mobyle.domain.repository.ActivityRepository
import org.mobyle.domain.repository.AuthRepository
import org.mobyle.domain.repository.FollowRepository
import org.mobyle.domain.repository.ShelfRepository
import org.mobyle.domain.repository.SocialNotifier
import org.mobyle.domain.repository.UserProfileRepository
import org.mobyle.domain.usecase.auth.ValidateToken
import org.mobyle.domain.usecase.feed.ActivityAccess
import org.mobyle.domain.usecase.feed.GetFeed
import org.mobyle.domain.usecase.feed.GetMovieActivities
import org.mobyle.domain.usecase.feed.GetProfileActivities
import org.mobyle.domain.usecase.feed.HideActivity
import org.mobyle.domain.usecase.feed.LoveActivity
import org.mobyle.domain.usecase.feed.UnloveActivity
import org.mobyle.domain.usecase.shelves.ShelfAccess
import org.mobyle.domain.usecase.users.ProfileAccess
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class FeedRoutingTest {

    private val repository = mockk<ActivityRepository>(relaxed = true)
    private val follows = mockk<FollowRepository>(relaxed = true)
    private val profiles = mockk<UserProfileRepository>(relaxed = true)
    private val notifier = mockk<SocialNotifier>(relaxed = true)
    private val authRepository = mockk<AuthRepository>()

    private fun record(id: Long, actor: String, visibility: Visibility, hidden: Boolean = false) = ActivityRecord(
        id = id, actorId = actor, type = ActivityType.NEW_SHELF, visibility = visibility,
        subjectType = ActivitySubject.SHELF, subjectId = 1, payload = JsonObject(emptyMap()),
        createdAt = Instant.fromEpochSeconds(0), hidden = hidden
    )

    private fun profile(id: String) = ProfileRecord(
        id = id, username = id, handle = null, displayName = null, pronouns = null, bio = null, avatarUrl = null,
        bannerUrl = null, accentColor = null, links = emptyList(), featured = false
    )

    @BeforeTest
    fun setUp() {
        coEvery { authRepository.validateToken("ana-token") } returns
            Result.success(JWTClaims("ana", "ana@example.com", "ana", null, 0, Long.MAX_VALUE))
        coEvery { authRepository.validateToken("bia-token") } returns
            Result.success(JWTClaims("bia", "bia@example.com", "bia", null, 0, Long.MAX_VALUE))
        every { follows.status(any(), any()) } returns FollowStatus.of(false, false)
        every { follows.userSummary(any()) } answers { UserSummary(firstArg(), firstArg(), null, null, null, null) }
        every { profiles.getProfile(any()) } returns null
        every { profiles.getProfile("ana") } returns profile("ana")
        every { repository.getRecord(any()) } returns null
        every { repository.getRecord(1) } returns record(1, "ana", Visibility.PUBLIC)
        every { repository.getRecord(2) } returns record(2, "ana", Visibility.MUTUAL)
        every { repository.getRecord(3) } returns record(3, "ana", Visibility.PUBLIC, hidden = true)
        every { repository.lovedBy(any(), any()) } returns false
        every { repository.love(any(), any()) } returns ActivityLoves(1, true)

        startKoin {
            modules(module {
                single { ValidateToken(authRepository) }
                single { ShelfAccess(mockk<ShelfRepository>(relaxed = true)) }
                single { ProfileAccess(profiles, get()) }
                single { ActivityAccess(repository, follows) }
                single { GetFeed(repository) }
                single { GetProfileActivities(repository, get()) }
                single { GetMovieActivities(repository) }
                single { LoveActivity(repository, get(), follows, notifier) }
                single { UnloveActivity(repository, get()) }
                single { HideActivity(repository, get()) }
            })
        }
    }

    @AfterTest
    fun tearDown() = stopKoin()

    private fun withApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            install(ContentNegotiation) { json() }
            configureStatusPages()
            routing { route(API_V1_PREFIX) { getFeedRouting() } }
        }
        block()
    }

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `the feed reads the scope and rejects an unknown one`() = withApp {
        every { repository.feed("bia", FeedScope.FOLLOWING, null, 20) } returns Cursor(emptyList(), null)

        val ok = client.get("/api/v1/feed?scope=following") { header("Authorization", "Bearer bia-token") }
        val bad = client.get("/api/v1/feed?scope=everyone") { header("Authorization", "Bearer bia-token") }

        assertEquals(HttpStatusCode.OK, ok.status)
        assertEquals(HttpStatusCode.BadRequest, bad.status)
        assertEquals("scope", json(bad.bodyAsText())["details"]!!.jsonObject["field"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a malformed cursor is 400`() = withApp {
        val response = client.get("/api/v1/feed?cursor=nope") { header("Authorization", "Bearer bia-token") }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `a profile shows others only what its owner lets them see`() = withApp {
        every { repository.userActivities(any(), any(), any(), any(), any(), any()) } returns Cursor(emptyList(), null)

        client.get("/api/v1/users/ana/activities") { header("Authorization", "Bearer bia-token") }
        client.get("/api/v1/users/me/activities") { header("Authorization", "Bearer ana-token") }

        verify { repository.userActivities("bia", "ana", setOf(Visibility.PUBLIC), false, null, 20) }
        verify { repository.userActivities("ana", "ana", Visibility.entries.toSet(), true, null, 20) }
    }

    @Test
    fun `the movie activities take a scope and a type, and only all is open to guests`() = withApp {
        every { repository.movieActivities(any(), any(), any(), any(), any(), any()) } returns Cursor(emptyList(), null)

        val guest = client.get("/api/v1/movies/10/activities?scope=all&type=ratings")
        val guestFollowing = client.get("/api/v1/movies/10/activities")
        client.get("/api/v1/movies/10/activities?scope=me&type=updates") { header("Authorization", "Bearer ana-token") }
        val unknownType = client.get("/api/v1/movies/10/activities?type=stars") { header("Authorization", "Bearer ana-token") }

        assertEquals(HttpStatusCode.OK, guest.status)
        assertEquals(HttpStatusCode.Unauthorized, guestFollowing.status)
        assertEquals(HttpStatusCode.BadRequest, unknownType.status)
        verify { repository.movieActivities(null, 10, FeedScope.ALL, MovieActivityKind.RATINGS, null, 20) }
        verify { repository.movieActivities("ana", 10, FeedScope.ME, MovieActivityKind.UPDATES, null, 20) }
    }

    @Test
    fun `loving tells the author once, and never for my own activity`() = withApp {
        val response = client.put("/api/v1/activities/1/love") { header("Authorization", "Bearer bia-token") }
        client.put("/api/v1/activities/1/love") { header("Authorization", "Bearer ana-token") }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("1", json(response.bodyAsText())["loves"]!!.jsonPrimitive.content)
        verify(exactly = 1) { notifier.notify("ana", any<SocialEvent.ActivityLoved>()) }
    }

    @Test
    fun `mutual and hidden activities of others do not exist for a non-mutual`() = withApp {
        for (id in listOf(2, 3, 99)) {
            val response = client.put("/api/v1/activities/$id/love") { header("Authorization", "Bearer bia-token") }
            assertEquals(HttpStatusCode.NotFound, response.status, "activity $id")
        }
        verify(exactly = 0) { repository.love(any(), any()) }
    }

    @Test
    fun `a mutual can love a mutual activity`() = withApp {
        every { follows.status("bia", "ana") } returns FollowStatus.of(true, true)

        val response = client.put("/api/v1/activities/2/love") { header("Authorization", "Bearer bia-token") }

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `only the author hides an activity`() = withApp {
        val byOther = client.put("/api/v1/activities/1/hidden") {
            header("Authorization", "Bearer bia-token")
            contentType(ContentType.Application.Json)
            setBody("""{"hidden":true}""")
        }
        val byAuthor = client.put("/api/v1/activities/1/hidden") {
            header("Authorization", "Bearer ana-token")
            contentType(ContentType.Application.Json)
            setBody("""{"hidden":true}""")
        }

        assertEquals(HttpStatusCode.Forbidden, byOther.status)
        assertEquals(HttpStatusCode.NoContent, byAuthor.status)
        verify(exactly = 1) { repository.setHidden(1, true) }
    }
}
