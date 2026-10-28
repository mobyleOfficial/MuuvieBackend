package org.mobyle.domain.usecase.movies

import org.mobyle.domain.model.Movie
import org.mobyle.domain.model.Page
import org.mobyle.domain.repository.WatchRepository
import org.mobyle.model.MovieListing

/**
 * Adds the viewer's Já assisti / Quero ver and rating label to the movies of a Catálogo list
 * (B14.2), in one query for the whole page. Logged out, the movies come back untouched.
 */
class AttachMovieStates(private val repository: WatchRepository) {
    operator fun invoke(viewerId: String?, movies: List<Movie>): List<Movie> {
        if (viewerId == null || movies.isEmpty()) return movies
        val states = repository.listStates(viewerId, movies.map { it.id })
        if (states.isEmpty()) return movies
        return movies.map { movie -> states[movie.id]?.let { movie.copy(me = it) } ?: movie }
    }

    operator fun invoke(viewerId: String?, listing: MovieListing): MovieListing =
        listing.copy(movies = invoke(viewerId, listing.movies))

    operator fun invoke(viewerId: String?, page: Page<Movie>): Page<Movie> =
        page.copy(results = invoke(viewerId, page.results))
}
