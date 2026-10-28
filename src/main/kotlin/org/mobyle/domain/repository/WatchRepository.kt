package org.mobyle.domain.repository

import kotlinx.datetime.LocalDate
import org.mobyle.domain.model.DiaryEntry
import org.mobyle.domain.model.Gif
import org.mobyle.domain.model.MovieActivity
import org.mobyle.domain.model.MovieCommunity
import org.mobyle.domain.model.MovieListState
import org.mobyle.domain.model.MovieRating
import org.mobyle.domain.model.Page
import org.mobyle.domain.model.PageRequest
import org.mobyle.domain.model.UserMovieRecord
import org.mobyle.domain.model.WatchLog
import org.mobyle.domain.model.WatchLogDraft
import org.mobyle.domain.model.WatchLogSummary
import org.mobyle.domain.model.WatchProgress
import org.mobyle.domain.model.vocabulary.Emotion
import org.mobyle.domain.model.vocabulary.Rating
import org.mobyle.domain.model.vocabulary.Visibility

/** A movie as the watch use cases need it. */
data class WatchableMovie(val id: Long, val tmdbId: Int?, val title: String, val posterPath: String?, val runtime: Int?)

/**
 * Watch sessions and the user's state for a movie (B2). Users are identified by their external id.
 *
 * Every write keeps user_movies in sync: a completed session puts the movie on Já assisti
 * (and off Quero ver), and the progress of the current cycle is summarized from the logs.
 */
interface WatchRepository {
    fun getMovie(movieId: Long): WatchableMovie?
    fun getUserMovie(userId: String, movieId: Long): UserMovieRecord?

    /** Adds a session to the current cycle. */
    fun createLog(userId: String, movieId: Long, draft: WatchLogDraft): WatchLog
    fun getLog(logId: Long): WatchLog?
    fun updateLog(logId: Long, draft: WatchLogDraft): WatchLog
    /** Deletes the session and recomputes the progress. The movie stays on Já assisti. */
    fun deleteLog(logId: Long)
    fun getLogs(ownerId: String, movieId: Long, visibilities: Set<Visibility>, page: PageRequest): Page<WatchLog>
    /** Every session of [ownerId] with one of [visibilities], newest first (the profile's Registros tab). */
    fun getUserLogs(ownerId: String, visibilities: Set<Visibility>, page: PageRequest): Page<DiaryEntry>
    fun summary(ownerId: String, movieId: Long, visibilities: Set<Visibility>): WatchLogSummary
    /** Opens the next cycle. The caller checks that the current one is complete. */
    fun startRewatch(userId: String, movieId: Long): WatchProgress

    /** Sets the label and its review; the shelves stay as they are (phase 13). */
    fun setRating(userId: String, movieId: Long, rating: Rating, review: String?, hasSpoiler: Boolean): MovieRating
    fun removeRating(userId: String, movieId: Long)

    fun watchingNow(userId: String): Long?
    fun setWatchingNow(userId: String, movieId: Long?)

    fun recentEmotions(userId: String, limit: Int): List<Emotion>
    fun recentGifs(userId: String, limit: Int): List<Gif>

    /** What the community did with the movie, counting only public Já assisti and Quero ver (F12.1). */
    fun community(movieId: Long): MovieCommunity

    /** Public sessions and public ratings of the movie by day, from [from] (inclusive) to [until] (exclusive) (B13.5). */
    fun activity(movieId: Long, from: LocalDate, until: LocalDate): MovieActivity

    /** The viewer's Já assisti / Quero ver and rating label for each movie that has any (B14.2). */
    fun listStates(userId: String, movieIds: List<Long>): Map<Long, MovieListState>
}
