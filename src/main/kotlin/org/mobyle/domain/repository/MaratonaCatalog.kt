package org.mobyle.domain.repository

import org.mobyle.domain.model.ChallengeCriteria
import org.mobyle.domain.model.Movie
import org.mobyle.domain.model.MovieFacts
import org.mobyle.domain.model.Page

/** The movie catalog as the maratona challenges read it (B7). */
interface MaratonaCatalog {
    /**
     * The metadata criteria test. A movie not enriched yet is enriched first, so a session
     * logged right after the movie entered the catalog still completes its challenges.
     */
    fun facts(movieId: Long): MovieFacts?

    /** Movies that fit [criteria], from TMDB discover (F7.3, "Descoberta"). */
    fun suggestions(criteria: ChallengeCriteria, page: Int): Page<Movie>
}
