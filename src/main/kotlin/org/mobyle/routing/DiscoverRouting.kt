package org.mobyle.routing

import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import org.mobyle.data.remote.auth.tryAuthenticateJWT
import org.mobyle.di.injection
import org.mobyle.domain.usecase.auth.ValidateToken
import org.mobyle.domain.usecase.discover.DiscoverLocadoras
import org.mobyle.domain.usecase.discover.DiscoverLovedMovies
import org.mobyle.domain.usecase.discover.DiscoverProfiles
import org.mobyle.domain.usecase.discover.DiscoverShelves
import org.mobyle.domain.usecase.discover.DiscoverTrendingMovies
import org.mobyle.domain.usecase.movies.AttachMovieStates

/** The discovery hub of the Catálogo (F5.6). Public items only; a logged-in viewer's own items are left out. */
fun Route.getDiscoverRouting() {
    val validateToken by injection<ValidateToken>()
    val discoverShelves by injection<DiscoverShelves>()
    val discoverLocadoras by injection<DiscoverLocadoras>()
    val discoverProfiles by injection<DiscoverProfiles>()
    val discoverTrendingMovies by injection<DiscoverTrendingMovies>()
    val discoverLovedMovies by injection<DiscoverLovedMovies>()
    val attachMovieStates by injection<AttachMovieStates>()

    get("/discover/shelves") {
        val viewerId = call.tryAuthenticateJWT(validateToken)?.claims?.userId
        val page = call.pageRequest() ?: return@get
        call.respond(discoverShelves(viewerId, call.parameters["q"], page))
    }

    get("/discover/locadoras") {
        val viewerId = call.tryAuthenticateJWT(validateToken)?.claims?.userId
        val page = call.pageRequest() ?: return@get
        call.respond(discoverLocadoras(viewerId, call.parameters["q"], page))
    }

    get("/discover/profiles") {
        val viewerId = call.tryAuthenticateJWT(validateToken)?.claims?.userId
        val page = call.pageRequest() ?: return@get
        call.respond(discoverProfiles(viewerId, call.parameters["q"], page))
    }

    // "Em alta no Muuvie" (B14.1): ?days=1..30, 7 by default.
    get("/discover/movies/trending") {
        val viewerId = call.tryAuthenticateJWT(validateToken)?.claims?.userId
        val page = call.pageRequest() ?: return@get
        val days = call.parameters["days"]?.let { it.toIntOrNull() ?: 0 } ?: DiscoverTrendingMovies.DEFAULT_DAYS
        call.respond(attachMovieStates(viewerId, discoverTrendingMovies(days, page)))
    }

    // "Mais amados no Muuvie" (B14.1).
    get("/discover/movies/loved") {
        val viewerId = call.tryAuthenticateJWT(validateToken)?.claims?.userId
        val page = call.pageRequest() ?: return@get
        call.respond(attachMovieStates(viewerId, discoverLovedMovies(page)))
    }
}
