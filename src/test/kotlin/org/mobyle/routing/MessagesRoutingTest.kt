package org.mobyle.routing

import io.ktor.client.plugins.websocket.webSocketSession
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
import io.ktor.server.websocket.WebSockets
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.mobyle.configureStatusPages
import org.mobyle.data.remote.push.PushSender
import org.mobyle.data.service.SocialNotifierImpl
import org.mobyle.data.service.WebSocketManager
import org.mobyle.data.service.WsTokenManager
import org.mobyle.domain.model.Conversation
import org.mobyle.domain.model.DeviceToken
import org.mobyle.domain.model.FollowStatus
import org.mobyle.domain.model.JWTClaims
import org.mobyle.domain.model.Message
import org.mobyle.domain.model.NotificationPrefs
import org.mobyle.domain.model.ProfileRecord
import org.mobyle.domain.model.UnreadCount
import org.mobyle.domain.model.UserSummary
import org.mobyle.domain.repository.AuthRepository
import org.mobyle.domain.repository.ConversationRepository
import org.mobyle.domain.repository.FollowRepository
import org.mobyle.domain.repository.NotificationRepository
import org.mobyle.domain.repository.RateLimiter
import org.mobyle.domain.repository.ShelfRepository
import org.mobyle.domain.repository.SocialNotifier
import org.mobyle.domain.repository.UserProfileRepository
import org.mobyle.domain.usecase.auth.ValidateToken
import org.mobyle.domain.usecase.messages.ConversationAccess
import org.mobyle.domain.usecase.messages.GetConversation
import org.mobyle.domain.usecase.messages.GetConversationWith
import org.mobyle.domain.usecase.messages.GetConversations
import org.mobyle.domain.usecase.messages.GetMessages
import org.mobyle.domain.usecase.messages.GetUnreadCount
import org.mobyle.domain.usecase.messages.MarkConversationRead
import org.mobyle.domain.usecase.messages.SendMessage
import org.mobyle.domain.usecase.notifications.GetNotificationPrefs
import org.mobyle.domain.usecase.notifications.RegisterDevice
import org.mobyle.domain.usecase.notifications.RemoveDevice
import org.mobyle.domain.usecase.notifications.SaveNotificationPrefs
import org.mobyle.domain.usecase.shelves.ShelfAccess
import org.mobyle.domain.usecase.users.ProfileAccess
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets

class MessagesRoutingTest {

    private val conversations = mockk<ConversationRepository>(relaxed = true)
    private val follows = mockk<FollowRepository>(relaxed = true)
    private val profiles = mockk<UserProfileRepository>(relaxed = true)
    private val notifications = mockk<NotificationRepository>(relaxed = true)
    private val pushSender = mockk<PushSender>()
    private val rateLimiter = mockk<RateLimiter>()
    private val authRepository = mockk<AuthRepository>()
    private val webSocketManager = WebSocketManager()
    private val wsTokens = WsTokenManager()

    private fun summary(id: String) = UserSummary(id, id, null, "Name $id", null, null)

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
        every { profiles.getProfile(any()) } answers { profile(firstArg()) }
        every { follows.userSummary(any()) } answers { summary(firstArg()) }
        every { follows.status(any(), any()) } returns FollowStatus.of(true, true)
        every { rateLimiter.tryAcquire(any(), any(), any()) } returns true
        every { conversations.getOrCreateConversation("ana", "bia") } returns 7
        every { conversations.addMessage(7, "ana", any()) } answers {
            Message(70, 7, "ana", thirdArg(), system = false, mine = true, createdAt = Clock.System.now())
        }
        every { conversations.unreadCount("bia") } returns UnreadCount(1, 1)
        every { notifications.prefs(any()) } returns NotificationPrefs()
        every { notifications.devices("bia") } returns listOf(DeviceToken("android", "bia-phone", "en"))
        coEvery { pushSender.send(any()) } returns emptySet()

        startKoin {
            modules(module {
                single { ValidateToken(authRepository) }
                single { ShelfAccess(mockk<ShelfRepository>(relaxed = true)) }
                single { ProfileAccess(profiles, get()) }
                single<SocialNotifier> { SocialNotifierImpl(webSocketManager, notifications, pushSender) }
                single { webSocketManager }
                single { wsTokens }
                single { ConversationAccess(conversations) }
                single { GetConversations(conversations) }
                single { GetConversation(get()) }
                single { GetConversationWith(conversations) }
                single { GetMessages(conversations, get()) }
                single { SendMessage(conversations, follows, get(), rateLimiter) }
                single { MarkConversationRead(conversations, get(), get()) }
                single { GetUnreadCount(conversations) }
                single { RegisterDevice(notifications) }
                single { RemoveDevice(notifications) }
                single { GetNotificationPrefs(notifications) }
                single { SaveNotificationPrefs(notifications) }
            })
        }
    }

    @AfterTest
    fun tearDown() = stopKoin()

    private fun withApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            install(ContentNegotiation) { json() }
            install(WebSockets)
            configureStatusPages()
            routing {
                route(API_V1_PREFIX) { getMessagesRouting() }
                getWebSocketRouting()
            }
        }
        block()
    }

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    private suspend fun ApplicationTestBuilder.send(token: String, to: String, body: String) =
        client.post("/api/v1/users/$to/messages") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"body":${Json.encodeToString(String.serializer(), body)}}""")
        }

    @Test
    fun `a message reaches the recipient on the WebSocket in under a second`() = withApp {
        val wsClient = createClient { install(ClientWebSockets) }
        val nonce = wsTokens.generateToken("bia")
        val session = wsClient.webSocketSession("/ws?token=$nonce")
        // Warm-up: the first request of a cold JVM (CI runner) pays for class loading and JIT,
        // which is not delivery latency. Each message reaches the recipient as two frames.
        send("ana-token", "bia", "Oi")
        repeat(2) { withTimeout(10_000) { session.incoming.receive() } }

        val started = System.currentTimeMillis()
        val response = send("ana-token", "bia", "Viu Suspiria?")
        val frame = withTimeout(1_000) { session.incoming.receive() as Frame.Text }

        assertEquals(HttpStatusCode.Created, response.status)
        val event = json(frame.readText())
        assertEquals("message", event["type"]!!.jsonPrimitive.content)
        assertEquals("Viu Suspiria?", event["payload"]!!.jsonObject["body"]!!.jsonPrimitive.content)
        assertTrue(System.currentTimeMillis() - started < 1_000)
        coVerify(timeout = 1_000) {
            pushSender.send(match { messages ->
                messages.single().let { it.token == "bia-phone" && it.title == "Name ana" && it.data["type"] == "message" }
            })
        }
    }

    @Test
    fun `only mutuals can message each other`() = withApp {
        every { follows.status("ana", "bia") } returns FollowStatus.of(true, false)

        val response = send("ana-token", "bia", "Oi")

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertEquals("not_mutual", json(response.bodyAsText())["error"]!!.jsonPrimitive.content)
        verify(exactly = 0) { conversations.addMessage(any(), any(), any()) }
    }

    @Test
    fun `an empty or too long message is 400 and too many are 429`() = withApp {
        assertEquals(HttpStatusCode.BadRequest, send("ana-token", "bia", "   ").status)
        assertEquals(HttpStatusCode.BadRequest, send("ana-token", "bia", "x".repeat(2001)).status)

        every { rateLimiter.tryAcquire("message:ana", 30, 60_000) } returns false
        assertEquals(HttpStatusCode.TooManyRequests, send("ana-token", "bia", "Oi").status)
    }

    @Test
    fun `a conversation of others does not exist`() = withApp {
        every { conversations.conversation("bia", 9) } returns null

        val response = client.get("/api/v1/conversations/9/messages") { header("Authorization", "Bearer bia-token") }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `reading a conversation answers the new badge`() = withApp {
        every { conversations.conversation("bia", 7) } returns
            Conversation(7, summary("ana"), true, null, 1, Instant.fromEpochSeconds(0))
        every { conversations.unreadCount("bia") } returns UnreadCount(0, 0)

        val response = client.put("/api/v1/conversations/7/read") { header("Authorization", "Bearer bia-token") }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("0", json(response.bodyAsText())["messages"]!!.jsonPrimitive.content)
        verify { conversations.markRead(7, "bia") }
    }

    @Test
    fun `a device needs a known platform and a token`() = withApp {
        val bad = client.put("/api/v1/users/me/devices") {
            header("Authorization", "Bearer bia-token")
            contentType(ContentType.Application.Json)
            setBody("""{"platform":"blackberry","token":"t"}""")
        }
        val ok = client.put("/api/v1/users/me/devices") {
            header("Authorization", "Bearer bia-token")
            contentType(ContentType.Application.Json)
            setBody("""{"platform":"android","token":"t1","locale":"pt-BR"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, bad.status)
        assertEquals(HttpStatusCode.NoContent, ok.status)
        verify { notifications.saveDevice("bia", DeviceToken("android", "t1", "pt-BR")) }
    }
}
