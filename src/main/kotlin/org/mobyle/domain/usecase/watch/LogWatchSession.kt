package org.mobyle.domain.usecase.watch

import org.mobyle.domain.model.NotFoundException
import org.mobyle.domain.model.ValidationException
import org.mobyle.domain.model.WatchLog
import org.mobyle.domain.repository.ShelfRepository
import org.mobyle.domain.repository.WatchRepository
import org.mobyle.domain.usecase.feed.ActivityPublisher
import org.mobyle.domain.usecase.gamification.GamificationHook
import org.mobyle.domain.usecase.maratonas.MaratonaAutoLinker

/**
 * Registers a watch session (F2.1), from the full form or the one-tap "Já assisti".
 * A completed session moves the movie to Já assisti. The movie is given by its database id
 * or, for movies seen only on TMDB, by its TMDB id.
 */
class LogWatchSession(
    private val repository: WatchRepository,
    private val shelves: ShelfRepository,
    private val activities: ActivityPublisher,
    // Completes maratona challenges by their criteria (B7).
    private val maratonas: MaratonaAutoLinker? = null,
    // XP, missions and bichinhos (B10).
    private val gamification: GamificationHook? = null
) {
    operator fun invoke(userId: String, movieId: Long?, tmdbId: Int?, title: String?, input: WatchLogInput): WatchLog {
        if (movieId == null && tmdbId == null) throw ValidationException("movieId", "movieId or tmdbId is required")
        val resolvedMovieId = shelves.resolveMovieId(movieId, tmdbId, title) ?: throw NotFoundException("Movie not found")
        val movie = repository.getMovie(resolvedMovieId) ?: throw NotFoundException("Movie not found")
        val draft = watchLogDraftOf(input, movie.runtime, today())
        val log = repository.createLog(userId, resolvedMovieId, draft)
        activities.sessionLogged(userId, movie, log)
        maratonas?.sessionLogged(userId, log)
        gamification?.sessionLogged(userId, log)
        return log.copy(self = true)
    }
}
