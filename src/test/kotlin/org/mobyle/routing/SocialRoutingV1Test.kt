package org.mobyle.routing

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.mobyle.configureStatusPages
import org.mobyle.domain.model.FollowEdge
import org.mobyle.domain.model.FollowStatus
import org.mobyle.domain.model.JWTClaims
import org.mobyle.domain.model.Page
import org.mobyle.domain.model.PageRequest
import org.mobyle.domain.model.ProfileRecord
import org.mobyle.domain.model.SocialEvent
import org.mobyle.domain.model.SystemMessage
import org.mobyle.domain.model.UserSummary
import org.mobyle.domain.repository.AuthRepository
import org.mobyle.domain.repository.ConversationRepository
import org.mobyle.domain.repository.FollowRepository
import org.mobyle.domain.repository.ProfileRepository
import org.mobyle.domain.repository.RateLimiter
import org.mobyle.domain.repository.ShelfRepository
import org.mobyle.domain.repository.SocialNotifier
import org.mobyle.domain.repository.UserProfileRepository
import org.mobyle.domain.usecase.auth.ValidateToken
import org.mobyle.domain.usecase.follow.FollowAccount
import org.mobyle.domain.usecase.follow.GetFollowList
import org.mobyle.domain.usecase.follow.GetFollowStatus
import org.mobyle.domain.usecase.follow.MutualChangeNotice
import org.mobyle.domain.usecase.follow.UnfollowAccount
import org.mobyle.domain.usecase.shelves.ShelfAccess
import org.mobyle.domain.usecase.social.SearchUsers
import org.mobyle.domain.usecase.users.ProfileAccess
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SocialRoutingV1Test {

    private val profileRepository = mockk<ProfileRepository>()
    private val userProfiles = mockk<UserProfileRepository>(relaxed = true)
    private val follows = mockk<FollowRepository>(relaxed = true)
    private val conversations = mockk<ConversationRepository>(relaxed = true)
    private val notifier = mockk<SocialNotifier>(relaxed = true)
    private val rateLimiter = mockk<RateLimiter>()
    private val authRepository = mockk<AuthRepository>()

    private fun summary(id: String) = UserSummary(id, id, null, null, null, null)

    private fun record(id: String) = ProfileRecord(
        id = id, username = id, handle = null, displayName = null, pronouns = null, bio = null, avatarUrl = null,
        bannerUrl = null, accentColor = null, links = emptyList(), featured = false
    )

    @BeforeTest
    fun setUp() {
        coEvery { authRepository.validateToken(any()) } returns
            Result.success(JWTClaims("me", "me@example.com", "me", null, 0, Long.MAX_VALUE))
        every { userProfiles.getProfile(any()) } returns null
        every { userProfiles.getProfile("u9") } returns record("u9")
        every { follows.userSummary(any()) } answers { summary(firstArg()) }
        every { rateLimiter.tryAcquire(any(), any(), any()) } returns true
        every { follows.followers("u9", PageRequest(2, 2)) } returns
            Page(listOf(FollowEdge(summary("u3"), Instant.fromEpochSeconds(10))), 2, 2, 3, false)
        every { follows.statuses("me", listOf("u3")) } returns mapOf("u3" to FollowStatus.of(true, true))

        startKoin {
            modules(module {
                single { ValidateToken(authRepository) }
                single { SearchUsers(profileRepository) }
                single { ShelfAccess(mockk<ShelfRepository>(relaxed = true)) }
                single { ProfileAccess(userProfiles, get()) }
                single { MutualChangeNotice(conversations, notifier) }
                single { FollowAccount(follows, notifier, rateLimiter, get()) }
                single { UnfollowAccount(follows, get()) }
                single { GetFollowStatus(follows) }
                single { GetFollowList(follows) }
            })
        }
    }

    @AfterTest
    fun tearDown() = stopKoin()

    private fun withApp(block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            install(ContentNegotiation) { json() }
            configureStatusPages()
            routing {
                route(API_V1_PREFIX) { getSocialRouting() }
            }
        }
        block()
    }

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `v1 followers returns the page envelope with the relation to each user`() = withApp {
        val response = client.get("/api/v1/users/u9/followers?page=2&limit=2") {
            header("Authorization", "Bearer token")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = json(response.bodyAsText())
        assertEquals(1, body["results"]!!.jsonArray.size)
        assertEquals(2, body["page"]!!.jsonPrimitive.int)
        assertEquals(3, body["totalResults"]!!.jsonPrimitive.int)
        val card = body["results"]!!.jsonArray.first().jsonObject
        assertEquals("u3", card["user"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals(true, card["mutual"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `v1 rejects an invalid limit`() = withApp {
        val response = client.get("/api/v1/users/u9/followers?limit=500") {
            header("Authorization", "Bearer token")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("invalid_pagination", json(response.bodyAsText())["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun `following back makes it mutual, tells the user and reopens the conversation`() = withApp {
        every { follows.status("me", "u9") } returnsMany listOf(FollowStatus.of(false, true), FollowStatus.of(true, true))
        every { follows.follow("me", "u9") } returns true
        every { conversations.findConversation("me", "u9") } returns 5

        val response = client.post("/api/v1/users/u9/follow") { header("Authorization", "Bearer token") }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(true, json(response.bodyAsText())["mutual"]!!.jsonPrimitive.boolean)
        verify { notifier.notify("u9", SocialEvent.NewFollower(summary("me"))) }
        verify { conversations.addMessage(5, null, SystemMessage.MUTUAL_RESTORED) }
    }

    @Test
    fun `unfollowing a mutual turns the conversation read-only`() = withApp {
        every { follows.status("me", "u9") } returnsMany listOf(FollowStatus.of(true, true), FollowStatus.of(false, true))
        every { follows.unfollow("me", "u9") } returns true
        every { conversations.findConversation("me", "u9") } returns 5

        val response = client.delete("/api/v1/users/u9/follow") { header("Authorization", "Bearer token") }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(false, json(response.bodyAsText())["following"]!!.jsonPrimitive.boolean)
        verify { conversations.addMessage(5, null, SystemMessage.MUTUAL_LOST) }
    }

    @Test
    fun `more than the follow limit per minute is 429`() = withApp {
        every { follows.status("me", "u9") } returns FollowStatus.of(false, false)
        every { rateLimiter.tryAcquire("follow:me", 10, 60_000) } returns false

        val response = client.post("/api/v1/users/u9/follow") { header("Authorization", "Bearer token") }

        assertEquals(HttpStatusCode.TooManyRequests, response.status)
        verify(exactly = 0) { follows.follow(any(), any()) }
    }

    @Test
    fun `follow status answers the three flags`() = withApp {
        every { follows.status("me", "u9") } returns FollowStatus.of(false, true)

        val response = client.get("/api/v1/users/u9/follow-status") { header("Authorization", "Bearer token") }

        val body = json(response.bodyAsText())
        assertEquals(false, body["following"]!!.jsonPrimitive.boolean)
        assertEquals(true, body["followedBy"]!!.jsonPrimitive.boolean)
        assertEquals(false, body["mutual"]!!.jsonPrimitive.boolean)
    }
}
