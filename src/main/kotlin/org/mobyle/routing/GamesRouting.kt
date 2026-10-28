package org.mobyle.routing

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import org.mobyle.data.remote.auth.authenticateJWT
import org.mobyle.di.injection
import org.mobyle.domain.usecase.auth.ValidateToken
import org.mobyle.domain.usecase.games.CreatePartyInput
import org.mobyle.domain.usecase.games.GetPosterRound
import org.mobyle.domain.usecase.games.WatchParties

@Serializable
data class PartyScheduleRequest(val inSeconds: Int = 0)

@Serializable
data class PartyMessageRequest(val body: String = "")

/** Watch parties (F10.4) and the poster game (F10.5) of the v1 API (B10.4, B10.5). */
fun Route.getGamesRouting() {
    val validateToken by injection<ValidateToken>()
    val parties by injection<WatchParties>()
    val posterRound by injection<GetPosterRound>()

    post("/watch-parties") {
        val principal = call.authenticateJWT(validateToken) ?: return@post
        val input = call.receive<CreatePartyInput>()
        call.respond(HttpStatusCode.Created, parties.create(principal.claims.userId, input))
    }

    get("/watch-parties/{code}") {
        val principal = call.authenticateJWT(validateToken) ?: return@get
        call.respond(parties.get(principal.claims.userId, call.parameters["code"]!!))
    }

    post("/watch-parties/{code}/members") {
        val principal = call.authenticateJWT(validateToken) ?: return@post
        call.respond(parties.join(principal.claims.userId, call.parameters["code"]!!))
    }

    delete("/watch-parties/{code}/members/me") {
        val principal = call.authenticateJWT(validateToken) ?: return@delete
        parties.leave(principal.claims.userId, call.parameters["code"]!!)
        call.respond(HttpStatusCode.NoContent)
    }

    post("/watch-parties/{code}/start") {
        val principal = call.authenticateJWT(validateToken) ?: return@post
        val request = call.receive<PartyScheduleRequest>()
        call.respond(parties.schedule(principal.claims.userId, call.parameters["code"]!!, request.inSeconds))
    }

    post("/watch-parties/{code}/messages") {
        val principal = call.authenticateJWT(validateToken) ?: return@post
        val request = call.receive<PartyMessageRequest>()
        call.respond(parties.send(principal.claims.userId, call.parameters["code"]!!, request.body))
    }

    get("/games/poster/round") {
        call.respond(posterRound())
    }
}
