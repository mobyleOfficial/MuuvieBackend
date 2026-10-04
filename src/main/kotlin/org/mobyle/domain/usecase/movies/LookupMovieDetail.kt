package org.mobyle.domain.usecase.movies

import kotlinx.coroutines.runBlocking
import org.mobyle.domain.model.MovieDetail
import org.mobyle.domain.repository.MoviesRepository

class LookupMovieDetail(private val repository: MoviesRepository) {
    operator fun invoke(id: Long?, tmdbId: Int?, filmowId: String?): MovieDetail? = runBlocking {
        repository.lookupMovieDetail(id, tmdbId, filmowId)
    }
}
