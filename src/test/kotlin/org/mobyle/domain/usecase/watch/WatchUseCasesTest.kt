package org.mobyle.domain.usecase.watch

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import org.mobyle.domain.model.ConflictException
import org.mobyle.domain.model.NotFoundException
import org.mobyle.domain.model.UserMovieRecord
import org.mobyle.domain.model.ValidationException
import org.mobyle.domain.model.WatchLog
import org.mobyle.domain.model.vocabulary.Emotion
import org.mobyle.domain.model.vocabulary.Rating
import org.mobyle.domain.model.vocabulary.Visibility
import org.mobyle.domain.model.vocabulary.WatchMode
import org.mobyle.domain.repository.CommentThreadRepository
import org.mobyle.domain.repository.ShelfRepository
import org.mobyle.domain.repository.WatchRepository
import org.mobyle.domain.repository.WatchableMovie
import org.mobyle.domain.usecase.comments.CommentAccess
import org.mobyle.domain.usecase.comments.PostComment
import org.mobyle.domain.usecase.shelves.ShelfAccess
import org.mobyle.domain.usecase.feed.ActivityPublisher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WatchUseCasesTest {

    private val repository = mockk<WatchRepository>(relaxed = true)
    private val shelves = mockk<ShelfRepository>(relaxed = true)
    private val activities = mockk<ActivityPublisher>(relaxed = true)
    private val today = LocalDate(2026, 10, 26)

    private fun record(status: String = "none", percent: Int? = null, cycle: Int = 1) = UserMovieRecord(
        id = 1, status = status, rating = null, review = null, reviewHasSpoiler = false, ratedAt = null,
        rewatches = cycle - 1, currentCycle = cycle, progressMinutes = null, progressPercent = percent
    )

    private fun log(owner: String, visibility: Visibility) = WatchLog(
        id = 5, userId = owner, movieId = 10, watchedOn = today, cycle = 1, mode = WatchMode.WATCHED,
        minutesWatched = null, percent = 100, completed = true, venue = null, providerName = null,
        emotions = emptyList(), gif = null, comment = null, hasSpoiler = false, visibility = visibility,
        createdAt = Instant.fromEpochSeconds(0), updatedAt = Instant.fromEpochSeconds(0)
    )

    @Test
    fun `minutes cannot pass the runtime, or 600 when it is unknown`() {
        val error = assertFailsWith<ValidationException> {
            watchLogDraftOf(WatchLogInput(mode = "minutes", minutesWatched = 121), runtime = 120, today)
        }
        assertEquals("minutesWatched", error.field)

        assertEquals(600, watchLogDraftOf(WatchLogInput(mode = "minutes", minutesWatched = 600), null, today).minutesWatched)
        assertFailsWith<ValidationException> {
            watchLogDraftOf(WatchLogInput(mode = "minutes", minutesWatched = 601), null, today)
        }
    }

    @Test
    fun `progress is resolved against the runtime`() {
        val half = watchLogDraftOf(WatchLogInput(mode = "percent", percent = 50), runtime = 120, today)
        assertEquals(60, half.minutesWatched)
        assertEquals(false, half.completed)

        val toTheEnd = watchLogDraftOf(WatchLogInput(mode = "minutes", minutesWatched = 120), runtime = 120, today)
        assertEquals(100, toTheEnd.percent)
        assertTrue(toTheEnd.completed)

        // The one-tap "Já assisti": only the date, everything else defaults.
        val oneTap = watchLogDraftOf(WatchLogInput(), runtime = 120, today)
        assertEquals(WatchMode.WATCHED, oneTap.mode)
        assertEquals(today, oneTap.watchedOn)
        assertEquals(Visibility.PUBLIC, oneTap.visibility)
        assertTrue(oneTap.completed)
    }

    @Test
    fun `emotions, dates and gifs are validated`() {
        val seven = Emotion.entries.take(7).map { it.wireValue }
        assertEquals("emotions", assertFailsWith<ValidationException> { watchLogDraftOf(WatchLogInput(emotions = seven), 100, today) }.field)
        assertEquals("emotions", assertFailsWith<ValidationException> { watchLogDraftOf(WatchLogInput(emotions = listOf("rage")), 100, today) }.field)
        assertEquals("watchedOn", assertFailsWith<ValidationException> { watchLogDraftOf(WatchLogInput(watchedOn = "2026-10-28"), 100, today) }.field)
        assertEquals(
            "gif",
            assertFailsWith<ValidationException> {
                watchLogDraftOf(WatchLogInput(gif = org.mobyle.domain.model.Gif("g", "http://x.gif")), 100, today)
            }.field
        )
        // Tomorrow is accepted for users east of UTC.
        assertEquals(LocalDate(2026, 10, 27), watchLogDraftOf(WatchLogInput(watchedOn = "2026-10-27"), 100, today).watchedOn)
    }

    @Test
    fun `rewatch needs the current cycle finished`() {
        every { repository.getMovie(10) } returns WatchableMovie(10, 100, "A", null, 100)
        val startRewatch = StartRewatch(repository)

        every { repository.getUserMovie("ana", 10) } returns record(percent = 40)
        assertFailsWith<ConflictException> { startRewatch("ana", 10) }

        // On Já assisti with no logs (marked by hand or imported) counts as finished.
        every { repository.getUserMovie("ana", 10) } returns record(status = "watched")
        startRewatch("ana", 10)
        // A rewatch in progress is not finished, even though the movie is still on Já assisti.
        every { repository.getUserMovie("ana", 10) } returns record(status = "watched", percent = 30, cycle = 2)
        assertFailsWith<ConflictException> { startRewatch("ana", 10) }
        verify(exactly = 1) { repository.startRewatch("ana", 10) }
    }

    @Test
    fun `rating takes one label and can log a full session with a verdict`() {
        every { repository.getMovie(10) } returns WatchableMovie(10, 100, "A", null, 100)
        val setRating = SetMovieRating(repository, activities)

        assertEquals("rating", assertFailsWith<ValidationException> { setRating("ana", 10, RatingInput(rating = "4.5")) }.field)

        setRating("ana", 10, RatingInput(rating = "loved", review = "  ", emotions = listOf("joy"), createLog = true, visibility = "mutual"))
        verify { repository.setRating("ana", 10, Rating.LOVED, null, false) }
        verify {
            repository.createLog("ana", 10, match {
                it.completed && it.emotions == listOf(Emotion.JOY) && it.visibility == Visibility.MUTUAL
            })
        }
    }

    @Test
    fun `abandoned and on hold cannot log a session, and abandoned stops watching now`() {
        every { repository.getMovie(10) } returns WatchableMovie(10, 100, "A", null, 100)
        every { repository.watchingNow("ana") } returns 10L
        val setRating = SetMovieRating(repository, activities)

        listOf("abandoned", "on_hold").forEach { label ->
            val error = assertFailsWith<ValidationException> { setRating("ana", 10, RatingInput(rating = label, createLog = true)) }
            assertEquals("createLog", error.field)
        }
        verify(exactly = 0) { repository.setRating(any(), any(), any(), any(), any()) }

        setRating("ana", 10, RatingInput(rating = "on_hold"))
        verify(exactly = 0) { repository.setWatchingNow("ana", null) }
        setRating("ana", 10, RatingInput(rating = "abandoned"))
        verify { repository.setWatchingNow("ana", null) }
        verify(exactly = 0) { repository.createLog(any(), any(), any()) }
    }

    @Test
    fun `nobody else sees or comments on a secret log`() {
        val comments = mockk<CommentThreadRepository>(relaxed = true)
        every { repository.getLog(5) } returns log(owner = "bia", visibility = Visibility.SECRET)
        every { shelves.areMutuals(any(), any()) } returns true
        val logAccess = WatchLogAccess(repository, ShelfAccess(shelves))
        val postComment = PostComment(comments, CommentAccess(comments, logAccess))

        assertFailsWith<NotFoundException> { postComment("ana", "watch_log", 5, null, "Oi", false) }
        assertFailsWith<NotFoundException> { logAccess.visibleLog(null, 5) }
        assertEquals("bia", logAccess.visibleLog("bia", 5).userId)
        assertEquals("targetType", assertFailsWith<ValidationException> { postComment("ana", "maratona", 5, null, "Oi", false) }.field)
    }
}
