package org.mobyle.domain.usecase.watch

import org.mobyle.domain.model.MovieCommunity
import org.mobyle.domain.repository.WatchRepository

/** The community block of the movie detail (B13.2): ratings by label, shelf counts and the emotions (B13.5). */
class GetMovieCommunity(private val repository: WatchRepository) {
    operator fun invoke(movieId: Long): MovieCommunity = repository.community(movieId)
}
