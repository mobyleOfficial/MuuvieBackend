package org.mobyle.domain.usecase.movies

import io.mockk.every
import io.mockk.mockk
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import org.mobyle.domain.model.ChallengeCriteria
import org.mobyle.domain.model.CineClubeSummary
import org.mobyle.domain.model.Maratona
import org.mobyle.domain.model.MaratonaChallenge
import org.mobyle.domain.model.MaratonaSummary
import org.mobyle.domain.model.MovieFacts
import org.mobyle.domain.model.Page
import org.mobyle.domain.model.PageRequest
import org.mobyle.domain.model.vocabulary.ChallengeType
import org.mobyle.domain.repository.CineClubeRepository
import org.mobyle.domain.repository.MaratonaCatalog
import org.mobyle.domain.repository.MaratonaRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GetMovieEventsTest {

    private val maratonas = mockk<MaratonaRepository>()
    private val catalog = mockk<MaratonaCatalog>()
    private val cineclubes = mockk<CineClubeRepository>()
    private val today = LocalDate(2026, 10, 27)
    private val events = GetMovieEvents(maratonas, catalog, cineclubes) { today }
    private val page = PageRequest(1, 50)

    private val horror = criteria("""{"all":[{"field":"genre","op":"in","value":[27]}]}""")
    private val drama = criteria("""{"all":[{"field":"genre","op":"in","value":[18]}]}""")
    private val suspiria = MovieFacts(
        movieId = 7, genreIds = setOf(27), year = 1977, countries = setOf("IT"), originalLanguage = "it",
        directorIds = emptySet(), castIds = emptySet(), runtime = 99, keywordIds = emptySet(), voteAverage = 7.3
    )

    @Test
    fun `lists the maratonas whose challenges the movie fits, the viewer's first`() {
        every { maratonas.list(any(), any(), any()) } returns Page.of(
            listOf(summary(1, joined = false), summary(2, joined = false), summary(3, joined = true)), page, 3
        )
        every { maratonas.getById(1) } returns maratona(challenge("Um terror", horror), challenge("Um drama", drama))
        every { maratonas.getById(2) } returns maratona(challenge("Um drama", drama), challenge("Livre", null))
        every { maratonas.getById(3) } returns maratona(challenge("Terror italiano", horror))
        every { catalog.facts(7) } returns suspiria
        every { cineclubes.list(any(), any(), any(), any()) } returns Page.of(emptyList(), page, 0)

        val result = events("ana", 7)

        assertEquals(listOf(3L, 1L), result.maratonas.map { it.maratona.id })
        assertEquals(listOf("Um terror"), result.maratonas.last().challenges)
    }

    @Test
    fun `a failing side stays empty and the other still answers`() {
        val cineclube = mockk<CineClubeSummary>(relaxed = true)
        every { maratonas.list(any(), any(), any()) } throws IllegalStateException("boom")
        every { cineclubes.list(null, match { it.movieId == 7L }, any(), today) } returns Page.of(listOf(cineclube), page, 1)

        val result = events(null, 7)

        assertTrue(result.maratonas.isEmpty())
        assertEquals(listOf(cineclube), result.cineclubes)
    }

    private fun criteria(json: String) = Json.decodeFromString(ChallengeCriteria.serializer(), json)

    private fun summary(id: Long, joined: Boolean) = mockk<MaratonaSummary> {
        every { this@mockk.id } returns id
        every { this@mockk.joined } returns joined
    }

    private fun maratona(vararg challenges: MaratonaChallenge) = mockk<Maratona> {
        every { this@mockk.challenges } returns challenges.toList()
    }

    private fun challenge(description: String, criteria: ChallengeCriteria?) = MaratonaChallenge(
        id = description.hashCode().toLong(), categoryId = 1, description = description,
        type = if (criteria == null) ChallengeType.FREE else ChallengeType.CRITERIA,
        criteria = criteria, position = 0, daily = false
    )
}
