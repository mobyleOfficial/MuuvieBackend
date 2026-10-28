package org.mobyle.data.repository

import kotlinx.datetime.LocalDate
import org.mobyle.data.local.watch.WatchDataSource
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
import org.mobyle.domain.repository.WatchRepository
import org.mobyle.domain.repository.WatchableMovie

class WatchRepositoryImpl(private val watchDataSource: WatchDataSource) : WatchRepository {
    override fun getMovie(movieId: Long): WatchableMovie? = watchDataSource.getMovie(movieId)

    override fun getUserMovie(userId: String, movieId: Long): UserMovieRecord? = watchDataSource.getUserMovie(userId, movieId)

    override fun createLog(userId: String, movieId: Long, draft: WatchLogDraft): WatchLog =
        watchDataSource.createLog(userId, movieId, draft)

    override fun getLog(logId: Long): WatchLog? = watchDataSource.getLog(logId)

    override fun updateLog(logId: Long, draft: WatchLogDraft): WatchLog = watchDataSource.updateLog(logId, draft)

    override fun deleteLog(logId: Long) = watchDataSource.deleteLog(logId)

    override fun getLogs(ownerId: String, movieId: Long, visibilities: Set<Visibility>, page: PageRequest): Page<WatchLog> =
        watchDataSource.getLogs(ownerId, movieId, visibilities, page)

    override fun getUserLogs(ownerId: String, visibilities: Set<Visibility>, page: PageRequest): Page<DiaryEntry> =
        watchDataSource.getUserLogs(ownerId, visibilities, page)

    override fun summary(ownerId: String, movieId: Long, visibilities: Set<Visibility>): WatchLogSummary =
        watchDataSource.summary(ownerId, movieId, visibilities)

    override fun startRewatch(userId: String, movieId: Long): WatchProgress = watchDataSource.startRewatch(userId, movieId)

    override fun setRating(userId: String, movieId: Long, rating: Rating, review: String?, hasSpoiler: Boolean): MovieRating =
        watchDataSource.setRating(userId, movieId, rating, review, hasSpoiler)

    override fun removeRating(userId: String, movieId: Long) = watchDataSource.removeRating(userId, movieId)

    override fun watchingNow(userId: String): Long? = watchDataSource.watchingNow(userId)

    override fun setWatchingNow(userId: String, movieId: Long?) = watchDataSource.setWatchingNow(userId, movieId)

    override fun recentEmotions(userId: String, limit: Int): List<Emotion> = watchDataSource.recentEmotions(userId, limit)

    override fun recentGifs(userId: String, limit: Int): List<Gif> = watchDataSource.recentGifs(userId, limit)

    override fun community(movieId: Long): MovieCommunity = watchDataSource.community(movieId)

    override fun activity(movieId: Long, from: LocalDate, until: LocalDate): MovieActivity =
        watchDataSource.activity(movieId, from, until)

    override fun listStates(userId: String, movieIds: List<Long>): Map<Long, MovieListState> =
        watchDataSource.listStates(userId, movieIds)
}
