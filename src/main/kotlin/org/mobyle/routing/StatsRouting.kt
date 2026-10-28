package org.mobyle.routing

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import kotlinx.serialization.Serializable
import org.mobyle.data.remote.auth.ErrorResponse
import org.mobyle.data.remote.auth.authenticateJWT
import org.mobyle.data.remote.auth.tryAuthenticateJWT
import org.mobyle.di.injection
import org.mobyle.domain.usecase.auth.ValidateToken
import org.mobyle.domain.usecase.stats.GetRetrospective
import org.mobyle.domain.usecase.stats.GetStatsCalendar
import org.mobyle.domain.usecase.stats.GetStatsYears
import org.mobyle.domain.usecase.stats.GetYearStats
import org.mobyle.domain.usecase.stats.GetYearlyGoal
import org.mobyle.domain.usecase.stats.SetYearlyGoal

@Serializable
data class YearlyGoalRequest(val targetMovies: Int = 0)

/**
 * Charts, retrospective and yearly goals of the v1 API (B9). `{userId}` takes a user id, an
 * @handle or `me`; the charts count only the sessions the viewer can see.
 */
fun Route.getStatsRouting() {
    val validateToken by injection<ValidateToken>()
    val getYears by injection<GetStatsYears>()
    val getYearStats by injection<GetYearStats>()
    val getCalendar by injection<GetStatsCalendar>()
    val getRetrospective by injection<GetRetrospective>()
    val getGoal by injection<GetYearlyGoal>()
    val setGoal by injection<SetYearlyGoal>()

    get("/users/me/retrospectives") {
        val principal = call.authenticateJWT(validateToken) ?: return@get
        call.respond(getRetrospective.years(principal.claims.userId))
    }

    get("/users/me/retrospectives/{year}") {
        val principal = call.authenticateJWT(validateToken) ?: return@get
        val year = call.year() ?: return@get
        call.respond(getRetrospective(principal.claims.userId, year))
    }

    put("/users/me/goals/{year}") {
        val principal = call.authenticateJWT(validateToken) ?: return@put
        val year = call.year() ?: return@put
        val request = call.receive<YearlyGoalRequest>()
        call.respond(setGoal(principal.claims.userId, year, request.targetMovies))
    }

    delete("/users/me/goals/{year}") {
        val principal = call.authenticateJWT(validateToken) ?: return@delete
        val year = call.year() ?: return@delete
        setGoal.remove(principal.claims.userId, year)
        call.respond(HttpStatusCode.NoContent)
    }

    get("/users/{userId}/stats/years") {
        val (viewerId, ownerId) = call.viewerAndOwner(validateToken) ?: return@get
        call.respond(getYears(viewerId, ownerId))
    }

    get("/users/{userId}/stats/{year}") {
        val (viewerId, ownerId) = call.viewerAndOwner(validateToken) ?: return@get
        val year = call.year() ?: return@get
        call.respond(getYearStats(viewerId, ownerId, year))
    }

    get("/users/{userId}/stats/{year}/calendar") {
        val (viewerId, ownerId) = call.viewerAndOwner(validateToken) ?: return@get
        val year = call.year() ?: return@get
        call.respond(getCalendar(viewerId, ownerId, year))
    }

    get("/users/{userId}/goals/{year}") {
        val (viewerId, ownerId) = call.viewerAndOwner(validateToken) ?: return@get
        val year = call.year() ?: return@get
        call.respond(getGoal(viewerId, ownerId, year))
    }
}

/** The viewer (null when logged out) and the owner; `me` needs a login. Null when a 401 was sent. */
private suspend fun ApplicationCall.viewerAndOwner(validateToken: ValidateToken): Pair<String?, String>? {
    val userId = parameters["userId"]!!
    if (userId == "me") {
        val viewerId = authenticateJWT(validateToken)?.claims?.userId ?: return null
        return viewerId to viewerId
    }
    return tryAuthenticateJWT(validateToken)?.claims?.userId to userId
}

private suspend fun ApplicationCall.year(): Int? {
    val year = parameters["year"]?.toIntOrNull()
    if (year == null) respond(HttpStatusCode.BadRequest, ErrorResponse("validation_failed", "Invalid year"))
    return year
}
