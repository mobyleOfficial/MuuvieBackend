package org.mobyle.data.service

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.datetime.Instant
import org.mobyle.domain.model.ImportBundle
import org.mobyle.domain.model.ImportJob
import org.mobyle.domain.model.ImportOptions
import org.mobyle.domain.model.ImportedMovie
import org.mobyle.domain.model.MovieResolution
import org.mobyle.domain.model.vocabulary.ImportSource
import org.mobyle.domain.model.vocabulary.ImportStatus
import org.mobyle.domain.repository.ImportRepository
import org.mobyle.domain.repository.LetterboxdFilmLookup
import org.mobyle.domain.repository.MovieResolver
import kotlin.test.Test

class ImportJobRunnerLetterboxdTest {

    private val repository = mockk<ImportRepository>(relaxed = true)
    private val resolver = mockk<MovieResolver>(relaxed = true)
    private val letterboxd = mockk<LetterboxdFilmLookup>()

    private val job = ImportJob(
        id = 3, source = ImportSource.LETTERBOXD, status = ImportStatus.IMPORTING, inputRef = "export.zip",
        processed = 0, total = 0, preview = null, options = ImportOptions(), counts = null, error = null,
        createdAt = Instant.fromEpochSeconds(0), updatedAt = Instant.fromEpochSeconds(0), finishedAt = null, userId = "ana"
    )

    @Test
    fun `a Letterboxd film is matched by the TMDB id of its page, the rest by the title search`() {
        val pastLives = ImportedMovie("https://boxd.it/oNB8", "Past Lives", year = 2023)
        val unknown = ImportedMovie("https://boxd.it/zzzz", "Unknown", year = 1999)
        every { repository.getBundle(3) } returns ImportBundle(watched = listOf(pastLives, unknown))
        coEvery { letterboxd.tmdbId("https://boxd.it/oNB8") } returns 666277
        coEvery { letterboxd.tmdbId("https://boxd.it/zzzz") } returns null
        every { repository.knownTmdbId(any(), any()) } returns null
        coEvery { resolver.resolve(any()) } returns MovieResolution.Unmatched(emptyList())
        val runner = ImportJobRunner(
            repository = repository, readers = emptyList(), resolver = resolver, letterboxd = letterboxd,
            enrichment = mockk(relaxed = true), webSocketManager = mockk(relaxed = true),
            activities = mockk(relaxed = true), notifier = mockk(relaxed = true)
        )

        runner.write(job)

        verify(timeout = 5_000) {
            repository.write(
                job,
                match { bundle -> bundle.watched.first().tmdbId == 666277 && bundle.watched.last().tmdbId == null },
                any(), any(), any(), any()
            )
        }
        coVerify(exactly = 0) { resolver.resolve(pastLives) }
        coVerify { resolver.resolve(unknown) }
    }
}
