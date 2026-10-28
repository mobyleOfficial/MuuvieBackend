package org.mobyle.routing

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
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.mobyle.configureStatusPages
import org.mobyle.domain.model.JWTClaims
import org.mobyle.domain.model.Maratona
import org.mobyle.domain.model.MaratonaLevel
import org.mobyle.domain.model.MaratonaTeam
import org.mobyle.domain.model.Page
import org.mobyle.domain.model.PageRequest
import org.mobyle.domain.model.TeamRankingEntry
import org.mobyle.domain.model.UserSummary
import org.mobyle.domain.model.vocabulary.MaratonaStatus
import org.mobyle.domain.repository.ActivityRepository
import org.mobyle.domain.repository.AuthRepository
import org.mobyle.domain.repository.MaratonaCatalog
import org.mobyle.domain.repository.MaratonaRepository
import org.mobyle.domain.repository.PasswordHasher
import org.mobyle.domain.repository.ShelfRepository
import org.mobyle.domain.usecase.auth.ValidateToken
import org.mobyle.domain.usecase.feed.ActivityPublisher
import org.mobyle.domain.usecase.maratonas.CancelMaratona
import org.mobyle.domain.usecase.maratonas.CheckMaratonaSlug
import org.mobyle.domain.usecase.maratonas.CreateMaratona
import org.mobyle.domain.usecase.maratonas.CreateMaratonaFromTemplate
import org.mobyle.domain.usecase.maratonas.GetChallengeSuggestions
import org.mobyle.domain.usecase.maratonas.GetMaratona
import org.mobyle.domain.usecase.maratonas.GetMaratonaChallenges
import org.mobyle.domain.usecase.maratonas.GetMaratonaProgress
import org.mobyle.domain.usecase.maratonas.GetMaratonaRanking
import org.mobyle.domain.usecase.maratonas.GetMaratonaTemplates
import org.mobyle.domain.usecase.maratonas.JoinMaratona
import org.mobyle.domain.usecase.maratonas.LeaveMaratona
import org.mobyle.domain.usecase.maratonas.ListMaratonas
import org.mobyle.domain.usecase.maratonas.MaratonaAccess
import org.mobyle.domain.usecase.maratonas.SetChallengeLink
import org.mobyle.domain.usecase.maratonas.SetMaratonaCoOwners
import org.mobyle.domain.usecase.maratonas.UpdateMaratona
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class MaratonasRoutingTest {

    private val repository = mockk<MaratonaRepository>(relaxed = true)
    private val shelves = mockk<ShelfRepository>(relaxed = true)
    private val catalog = mockk<MaratonaCatalog>(relaxed = true)
    private val hasher = mockk<PasswordHasher>()
    private val activities = mockk<ActivityPublisher>(relaxed = true)
    private val activityRepository = mockk<ActivityRepository>(relaxed = true)
    private val authRepository = mockk<AuthRepository>()

    private val ana = UserSummary("ana", "ana", "ana.cine", "Ana", null, null)

    private fun maratona(status: MaratonaStatus = MaratonaStatus.ACTIVE) = Maratona(
        id = 1, slug = "terror-70", title = "Terror 70", description = null, logoUrl = null, accentColor = null,
        startsOn = LocalDate(2026, 10, 1), endsOn = LocalDate(2026, 10, 31), status = status, hasPassword = true,
        socialLink = null, trusted = false, templateId = null, owner = ana, coOwners = emptyList(), categories = emptyList(),
        levels = listOf(MaratonaLevel(5, "Maratonista", 3)), teams = listOf(MaratonaTeam(7, "Azul", null)), prizes = emptyList(),
        totalChallenges = 3, totalParticipants = 0, createdAt = Instant.fromEpochSeconds(0)
    )

    @BeforeTest
    fun setUp() {
        coEvery { authRepository.validateToken("ana-token") } returns
            Result.success(JWTClaims("ana", "ana@example.com", "ana", null, 0, Long.MAX_VALUE))
        coEvery { authRepository.validateToken("bia-token") } returns
            Result.success(JWTClaims("bia", "bia@example.com", "bia", null, 0, Long.MAX_VALUE))
        every { repository.get("terror-70") } returns maratona()
        every { repository.participation(any(), any()) } returns null
        every { repository.list(any(), any(), any()) } answers { Page.of(emptyList(), thirdArg<PageRequest>(), 0) }
        every { repository.passwordHash(1) } returns "hash"
        every { hasher.verify("pipoca", "hash") } returns true
        every { hasher.verify("errada", "hash") } returns false

        startKoin {
            modules(module {
                single { ValidateToken(authRepository) }
                single { MaratonaAccess(repository) }
                single { ListMaratonas(repository) }
                single { GetMaratona(get()) }
                single { CheckMaratonaSlug(repository) }
                single { CreateMaratona(repository, get(), hasher, activities) }
                single { UpdateMaratona(repository, get(), hasher) }
                single { CancelMaratona(repository, get()) }
                single { SetMaratonaCoOwners(repository, get(), shelves) }
                single { JoinMaratona(repository, get(), shelves, hasher, activities) }
                single { LeaveMaratona(repository, get(), shelves, activities) }
                single { GetMaratonaChallenges(repository, get()) }
                single { GetChallengeSuggestions(get(), catalog) }
                single { SetChallengeLink(repository, get(), shelves, activities) }
                single { GetMaratonaRanking(repository, get()) }
                single { GetMaratonaProgress(get(), activityRepository) }
                single { GetMaratonaTemplates() }
                single { CreateMaratonaFromTemplate(get()) }
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
                route(API_V1_PREFIX) { getMaratonasRouting() }
            }
        }
        block()
    }

    private fun error(body: String) = Json.parseToJsonElement(body).jsonObject["error"]!!.jsonPrimitive.content

    private val validBody = """
        {"slug":"terror-70","title":"Terror 70","startsOn":"2099-10-01","endsOn":"2099-10-31",
         "categories":[{"name":"Terror","challenges":[{"description":"A"},{"description":"B"},{"description":"C"}]}]}
    """.trimIndent()

    @Test
    fun `the list, the detail and the templates work without login`() = withApp {
        assertEquals(HttpStatusCode.OK, client.get("$API_V1_PREFIX/maratonas?q=terror&includeFinished=true").status)
        verify { repository.list(null, match { it.query == "terror" && it.includeFinished && !it.mine }, any()) }

        val detail = client.get("$API_V1_PREFIX/maratonas/terror-70")
        assertEquals(HttpStatusCode.OK, detail.status)
        assertEquals("terror-70", Json.parseToJsonElement(detail.bodyAsText()).jsonObject["slug"]!!.jsonPrimitive.content)

        val templates = Json.parseToJsonElement(client.get("$API_V1_PREFIX/maratona-templates").bodyAsText()).jsonArray
        assertEquals(5, templates.size)
    }

    @Test
    fun `my maratonas and the writes need a login`() = withApp {
        assertEquals(HttpStatusCode.Unauthorized, client.get("$API_V1_PREFIX/maratonas?mine=true").status)
        val post = client.post("$API_V1_PREFIX/maratonas") {
            contentType(ContentType.Application.Json)
            setBody(validBody)
        }
        assertEquals(HttpStatusCode.Unauthorized, post.status)
    }

    @Test
    fun `an unknown maratona is a 404`() = withApp {
        every { repository.get("nada") } returns null

        assertEquals(HttpStatusCode.NotFound, client.get("$API_V1_PREFIX/maratonas/nada").status)
    }

    @Test
    fun `a taken slug is a 409 and fewer than three challenges a 400`() = withApp {
        every { repository.slugTaken("terror-70") } returns true
        val taken = client.post("$API_V1_PREFIX/maratonas") {
            header("Authorization", "Bearer ana-token")
            contentType(ContentType.Application.Json)
            setBody(validBody)
        }
        assertEquals(HttpStatusCode.Conflict, taken.status)
        assertEquals("slug_taken", error(taken.bodyAsText()))

        val short = client.post("$API_V1_PREFIX/maratonas") {
            header("Authorization", "Bearer ana-token")
            contentType(ContentType.Application.Json)
            setBody(validBody.replace(""",{"description":"C"}""", ""))
        }
        assertEquals(HttpStatusCode.BadRequest, short.status)
        assertEquals("validation_failed", error(short.bodyAsText()))
    }

    @Test
    fun `joining with a wrong password is a 403 and a closed maratona a 409`() = withApp {
        val wrong = client.post("$API_V1_PREFIX/maratonas/terror-70/participation") {
            header("Authorization", "Bearer bia-token")
            contentType(ContentType.Application.Json)
            setBody("""{"password":"errada"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, wrong.status)
        assertEquals("wrong_password", error(wrong.bodyAsText()))

        every { repository.get("terror-70") } returns maratona(MaratonaStatus.FINISHED)
        val closed = client.post("$API_V1_PREFIX/maratonas/terror-70/participation") {
            header("Authorization", "Bearer bia-token")
            contentType(ContentType.Application.Json)
            setBody("""{"password":"pipoca"}""")
        }
        assertEquals(HttpStatusCode.Conflict, closed.status)
        assertEquals("maratona_closed", error(closed.bodyAsText()))
    }

    @Test
    fun `only the creator cancels`() = withApp {
        val response = client.post("$API_V1_PREFIX/maratonas/terror-70/cancel") { header("Authorization", "Bearer bia-token") }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertEquals("not_maratona_owner", error(response.bodyAsText()))
        verify(exactly = 0) { repository.setStatus(any(), any()) }
    }

    @Test
    fun `the ranking comes by user or by team`() = withApp {
        every { repository.ranking(1, any()) } answers { Page.of(emptyList(), secondArg<PageRequest>(), 0) }
        every { repository.teamRanking(1) } returns listOf(TeamRankingEntry(1, MaratonaTeam(7, "Azul", null), 2, 4, null))

        val teams = Json.parseToJsonElement(client.get("$API_V1_PREFIX/maratonas/terror-70/ranking?by=team").bodyAsText()).jsonObject
        assertEquals("Azul", teams["results"]!!.jsonArray[0].jsonObject["team"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.OK, client.get("$API_V1_PREFIX/maratonas/terror-70/ranking").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("$API_V1_PREFIX/maratonas/terror-70/ranking?by=pais").status)
    }

    @Test
    fun `the slug check says whether the address is free`() = withApp {
        every { repository.slugTaken("terror-70") } returns true

        val taken = Json.parseToJsonElement(client.get("$API_V1_PREFIX/maratonas/slug-available?slug=Terror-70").bodyAsText()).jsonObject
        assertEquals("false", taken["available"]!!.jsonPrimitive.content)
        val invalid = Json.parseToJsonElement(client.get("$API_V1_PREFIX/maratonas/slug-available?slug=a").bodyAsText()).jsonObject
        assertEquals("false", invalid["valid"]!!.jsonPrimitive.content)
    }
}
