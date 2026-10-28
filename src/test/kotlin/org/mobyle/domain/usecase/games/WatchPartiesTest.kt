package org.mobyle.domain.usecase.games

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.mobyle.domain.model.ConflictException
import org.mobyle.domain.model.ForbiddenException
import org.mobyle.domain.model.Movie
import org.mobyle.domain.model.NotFoundException
import org.mobyle.domain.model.SocialEvent
import org.mobyle.domain.model.ValidationException
import org.mobyle.domain.repository.MoviesRepository
import org.mobyle.domain.repository.SocialNotifier
import org.mobyle.domain.repository.UserProfileRepository
import org.mobyle.domain.repository.WatchRepository
import org.mobyle.domain.repository.WatchableMovie
import org.mobyle.model.MovieListing
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

class WatchPartiesTest {

    private class FakeClock(var now: Instant) : Clock {
        override fun now() = now
    }

    private val clock = FakeClock(Instant.parse("2026-10-27T20:00:00Z"))
    private val events = mutableListOf<Pair<String, SocialEvent>>()
    private val notifier = object : SocialNotifier {
        override fun notify(userId: String, event: SocialEvent) {
            events += userId to event
        }
    }
    private val profiles = mockk<UserProfileRepository> { every { getProfile(any()) } returns null }
    private val movies = mockk<WatchRepository> {
        every { getMovie(10) } returns WatchableMovie(10, 100, "Rashomon", "/r.jpg", 88)
        every { getMovie(99) } returns null
    }
    private val parties = WatchParties(profiles, movies, notifier, clock, Random(1))

    @Test
    fun `the host creates, friends join, chat and get the countdown`() {
        val created = parties.create("ana", CreatePartyInput(movieId = 10))
        assertEquals("Rashomon", created.title)
        assertEquals(6, created.code.length)
        assertTrue(created.self)

        val joined = parties.join("bia", created.code.lowercase())
        assertEquals(listOf("ana", "bia"), joined.members.map { it.id })
        assertEquals(listOf("ana"), events.map { it.first })

        parties.send("bia", created.code, " Pipoca pronta ")
        val started = parties.schedule("ana", created.code, 30)

        assertEquals(clock.now + 30.seconds, started.startAt)
        assertEquals("Pipoca pronta", started.messages.single().body)
        assertEquals(listOf("join", "message", "start"), events.map { (it.second as SocialEvent.WatchPartyChanged).event })
        assertEquals("bia", events.last().first)
    }

    @Test
    fun `only members read, only the host starts and the host leaving closes it`() {
        val code = parties.create("ana", CreatePartyInput(title = "Sessão de sexta")).code

        assertFailsWith<ForbiddenException> { parties.get("bia", code) }
        parties.join("bia", code)
        assertFailsWith<ForbiddenException> { parties.schedule("bia", code, 30) }
        assertFailsWith<ValidationException> { parties.schedule("ana", code, 1) }
        assertFailsWith<ValidationException> { parties.send("bia", code, "  ") }

        parties.leave("ana", code)
        assertFailsWith<NotFoundException> { parties.get("bia", code) }
    }

    @Test
    fun `a room needs a title or a movie and expires`() {
        assertFailsWith<ValidationException> { parties.create("ana", CreatePartyInput()) }
        assertFailsWith<NotFoundException> { parties.create("ana", CreatePartyInput(movieId = 99)) }

        val code = parties.create("ana", CreatePartyInput(title = "Maratona")).code
        clock.now += 7.hours
        assertFailsWith<NotFoundException> { parties.join("bia", code) }
    }

    @Test
    fun `a full room refuses new members`() {
        val code = parties.create("ana", CreatePartyInput(title = "Lotada")).code
        (1..29).forEach { parties.join("user$it", code) }

        assertFailsWith<ConflictException> { parties.join("late", code) }
        assertNull(parties.get("ana", code).startAt)
    }

    @Test
    fun `a poster round has the answer among four options`() = runBlocking {
        val moviesRepository = mockk<MoviesRepository>()
        coEvery { moviesRepository.getTrendingMovies(any()) } returns MovieListing(
            1, 5, (1..5).map { Movie(id = it.toLong(), tmdbId = it, title = "Filme $it", posterPath = "/$it.jpg") } +
                Movie(id = 6, title = "Sem pôster")
        )

        val round = GetPosterRound(moviesRepository, Random(3))()

        assertEquals(4, round.options.size)
        assertTrue(round.movie in round.options)
        assertTrue(round.options.all { it.posterPath != null })
    }
}
