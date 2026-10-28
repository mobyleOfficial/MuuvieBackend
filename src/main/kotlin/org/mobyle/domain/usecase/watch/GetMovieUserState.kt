package org.mobyle.domain.usecase.watch

import org.mobyle.domain.model.MovieRating
import org.mobyle.domain.model.MovieUserState
import org.mobyle.domain.model.WatchProgress
import org.mobyle.domain.model.vocabulary.WatchStatus
import org.mobyle.domain.repository.ShelfRepository
import org.mobyle.domain.repository.WatchRepository

/** The `me` block of the movie detail (F2.7): shelves, situation, progress and rating. */
class GetMovieUserState(private val repository: WatchRepository, private val shelves: ShelfRepository) {
    operator fun invoke(userId: String, movieId: Long): MovieUserState {
        val record = repository.getUserMovie(userId, movieId)
        val rating = record?.rating?.let { label ->
            MovieRating(
                id = record.id,
                rating = label,
                review = record.review,
                hasSpoiler = record.reviewHasSpoiler,
                ratedAt = record.ratedAt
            )
        }
        return MovieUserState(
            shelfIds = shelves.shelfIdsWithMovie(userId, movieId),
            status = record?.status?.let { WatchStatus.fromWireValue(it) },
            progress = record?.let { WatchProgress(it.currentCycle, it.progressMinutes, it.progressPercent, it.cycleCompleted) },
            rating = rating,
            rewatches = record?.rewatches ?: 0,
            watchingNow = repository.watchingNow(userId) == movieId
        )
    }
}
