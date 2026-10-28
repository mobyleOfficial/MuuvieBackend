package org.mobyle.domain.repository

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import org.mobyle.domain.model.Locadora
import org.mobyle.domain.model.Movie
import org.mobyle.domain.model.Page
import org.mobyle.domain.model.PageRequest
import org.mobyle.domain.model.ProfileCard
import org.mobyle.domain.model.Shelf

/**
 * The discovery hub of the Catálogo (F5.6): public shelves, locadoras and profiles, curated
 * first and then ranked by activity since [since]. [viewerId] is left out of the results.
 */
interface DiscoverRepository {
    fun shelves(viewerId: String?, query: String?, since: Instant, page: PageRequest): Page<Shelf>
    fun locadoras(viewerId: String?, query: String?, since: Instant, page: PageRequest): Page<Locadora>
    fun profiles(viewerId: String?, query: String?, since: Instant, page: PageRequest): Page<ProfileCard>

    /** Movies with the most people logging them publicly since [since] (B14.1). */
    fun trendingMovies(since: LocalDate, page: PageRequest): Page<Movie>

    /** Movies with the most Favoritado + Adorei labels, at least [minRatings] (B14.1). */
    fun lovedMovies(minRatings: Int, page: PageRequest): Page<Movie>
}
