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
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.mobyle.configureStatusPages
import org.mobyle.domain.model.JWTClaims
import org.mobyle.domain.repository.AuthRepository
import org.mobyle.domain.repository.StatsRepository
import org.mobyle.domain.usecase.auth.ValidateToken
import org.mobyle.domain.usecase.stats.GetRetrospective
import org.mobyle.domain.usecase.stats.GetStatsCalendar
import org.mobyle.domain.usecase.stats.GetStatsYears
import org.mobyle.domain.usecase.stats.GetYearStats
import org.mobyle.domain.usecase.stats.GetYearlyGoal
import org.mobyle.domain.usecase.stats.SetYearlyGoal
import org.mobyle.domain.usecase.stats.StatsAccess
import org.mobyle.domain.model.vocabulary.Visibility
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class StatsRoutingTest {

    private val repository = mockk<StatsRepository>(relaxed = true)
    private val access = mockk<StatsAccess>()
    private val authRepository = mockk<AuthRepository>()
    private val today = LocalDate(2026, 10, 27)

    @BeforeTest
    fun setUp() {
        coEvery { authRepository.validateToken("ana-token") } returns
            Result.success(JWTClaims("ana", "ana@example.com", "ana", null, 0, Long.MAX_VALUE))
        every { access.owner(any()) } answers { firstArg() }
        every { access.visibilities(any(), any()) } returns setOf(Visibility.PUBLIC)
        every { repository.logs(any(), any(), any()) } returns emptyList()
        every { repository.years(any(), any()) } returns listOf(2026)

        startKoin {
            modules(module {
                single { ValidateToken(authRepository) }
                single { GetStatsYears(repository, access) }
                single { GetYearStats(repository, access) { today } }
                single { GetStatsCalendar(repository, access) { today } }
                single { GetRetrospective(repository) { today } }
                single { GetYearlyGoal(repository, access) { today } }
                single { SetYearlyGoal(repository) { today } }
            })
        }
    }

    @AfterTest
    fun tearDown() = stopKoin()

    private fun withApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            install(ContentNegotiation) { json() }
            configureStatusPages()
            routing { route(API_V1_PREFIX) { getStatsRouting() } }
        }
        block()
    }

    @Test
    fun `public charts work without login and me needs one`() = withApp {
        val stats = client.get("$API_V1_PREFIX/users/ana/stats/2026")
        assertEquals(HttpStatusCode.OK, stats.status)
        assertEquals("2026", Json.parseToJsonElement(stats.bodyAsText()).jsonObject["year"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.OK, client.get("$API_V1_PREFIX/users/ana/stats/years").status)
        assertEquals(HttpStatusCode.OK, client.get("$API_V1_PREFIX/users/ana/stats/2026/calendar").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("$API_V1_PREFIX/users/me/stats/2026").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("$API_V1_PREFIX/users/me/retrospectives").status)
        assertEquals(
            HttpStatusCode.OK,
            client.get("$API_V1_PREFIX/users/me/stats/2026") { header("Authorization", "Bearer ana-token") }.status
        )
        verify { repository.logs("ana", 2026, any()) }
    }

    @Test
    fun `a bad year is a 400 and a year without movies a 404`() = withApp {
        assertEquals(HttpStatusCode.BadRequest, client.get("$API_V1_PREFIX/users/ana/stats/ano").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("$API_V1_PREFIX/users/ana/stats/1500").status)
        assertEquals(
            HttpStatusCode.NotFound,
            client.get("$API_V1_PREFIX/users/me/retrospectives/2026") { header("Authorization", "Bearer ana-token") }.status
        )
        every { repository.goal("ana", 2026) } returns null
        assertEquals(HttpStatusCode.NotFound, client.get("$API_V1_PREFIX/users/ana/goals/2026").status)
    }

    @Test
    fun `setting a goal validates the target`() = withApp {
        val bad = client.put("$API_V1_PREFIX/users/me/goals/2026") {
            header("Authorization", "Bearer ana-token")
            contentType(ContentType.Application.Json)
            setBody("""{"targetMovies":0}""")
        }
        assertEquals(HttpStatusCode.BadRequest, bad.status)

        val good = client.put("$API_V1_PREFIX/users/me/goals/2026") {
            header("Authorization", "Bearer ana-token")
            contentType(ContentType.Application.Json)
            setBody("""{"targetMovies":100}""")
        }
        assertEquals(HttpStatusCode.OK, good.status)
        verify { repository.setGoal("ana", 2026, 100) }
    }
}
