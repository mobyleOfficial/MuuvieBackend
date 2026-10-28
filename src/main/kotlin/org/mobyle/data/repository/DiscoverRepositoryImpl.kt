package org.mobyle.data.repository

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import org.mobyle.data.local.discover.DiscoverDataSource
import org.mobyle.data.local.locadoras.LocadoraDataSource
import org.mobyle.data.local.movies.MovieCatalogDataSource
import org.mobyle.data.local.profile.UserProfileDataSource
import org.mobyle.data.local.shelves.ShelfDataSource
import org.mobyle.domain.model.Locadora
import org.mobyle.domain.model.Movie
import org.mobyle.domain.model.Page
import org.mobyle.domain.model.PageRequest
import org.mobyle.domain.model.ProfileCard
import org.mobyle.domain.model.Shelf
import org.mobyle.domain.model.vocabulary.Visibility
import org.mobyle.domain.repository.DiscoverRepository

class DiscoverRepositoryImpl(
    private val discoverDataSource: DiscoverDataSource,
    private val shelfDataSource: ShelfDataSource,
    private val locadoraDataSource: LocadoraDataSource,
    private val userProfileDataSource: UserProfileDataSource,
    private val movieCatalogDataSource: MovieCatalogDataSource
) : DiscoverRepository {

    private val publicOnly = setOf(Visibility.PUBLIC)

    override fun shelves(viewerId: String?, query: String?, since: Instant, page: PageRequest): Page<Shelf> {
        val ids = discoverDataSource.shelfIds(viewerId, query, since, page)
        return ids.load { id ->
            shelfDataSource.getShelf(id)?.let { shelf ->
                shelf.copy(saved = viewerId != null && shelfDataSource.isSaved(id, viewerId))
            }
        }
    }

    override fun locadoras(viewerId: String?, query: String?, since: Instant, page: PageRequest): Page<Locadora> {
        val ids = discoverDataSource.locadoraIds(viewerId, query, since, page)
        // Counters and posters of a discovered locadora only cover its public shelves.
        return ids.load { locadoraDataSource.getLocadora(it, publicOnly) }
    }

    override fun profiles(viewerId: String?, query: String?, since: Instant, page: PageRequest): Page<ProfileCard> {
        val ids = discoverDataSource.userIds(viewerId, query, since, page)
        return ids.load { userId ->
            userProfileDataSource.getProfile(userId)?.let { record ->
                ProfileCard(
                    id = record.id,
                    username = record.username,
                    handle = record.handle,
                    displayName = record.displayName,
                    avatarUrl = record.avatarUrl,
                    accentColor = record.accentColor,
                    bio = record.bio,
                    featured = record.featured,
                    watched = userProfileDataSource.stats(userId, publicOnly).watched,
                    isFollowing = viewerId != null && userProfileDataSource.isFollowing(viewerId, userId)
                )
            }
        }
    }

    override fun trendingMovies(since: LocalDate, page: PageRequest): Page<Movie> =
        discoverDataSource.trendingMovieIds(since, page).load { movieCatalogDataSource.findById(it) }

    override fun lovedMovies(minRatings: Int, page: PageRequest): Page<Movie> =
        discoverDataSource.lovedMovieIds(minRatings, page).load { movieCatalogDataSource.findById(it) }

    private fun <I, T> Page<I>.load(loader: (I) -> T?): Page<T> =
        Page(results.mapNotNull(loader), page, totalPages, totalResults, hasNext)
}
