package org.mobyle.routing

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.serialization.Serializable
import org.mobyle.data.remote.auth.ErrorResponse
import org.mobyle.data.remote.auth.authenticateJWT
import org.mobyle.data.remote.auth.tryAuthenticateJWT
import org.mobyle.di.injection
import org.mobyle.domain.model.CineClubeFilter
import org.mobyle.domain.model.vocabulary.SessionProgress
import org.mobyle.domain.usecase.auth.ValidateToken
import org.mobyle.domain.usecase.cineclubes.CancelCineClube
import org.mobyle.domain.usecase.cineclubes.CheckCineClubeSlug
import org.mobyle.domain.usecase.cineclubes.CineClubeInput
import org.mobyle.domain.usecase.cineclubes.CreateCineClube
import org.mobyle.domain.usecase.cineclubes.GetCineClube
import org.mobyle.domain.usecase.cineclubes.GetCineClubeParticipants
import org.mobyle.domain.usecase.cineclubes.GetCineClubeProgress
import org.mobyle.domain.usecase.cineclubes.JoinCineClube
import org.mobyle.domain.usecase.cineclubes.LeaveCineClube
import org.mobyle.domain.usecase.cineclubes.ListCineClubes
import org.mobyle.domain.usecase.cineclubes.UpdateCineClube

@Serializable
data class JoinCineClubeRequest(val password: String? = null)

/** CineClubes of the v1 API (B8). Listing, detail, progress and participants work without login. */
fun Route.getCineClubesRouting() {
    val validateToken by injection<ValidateToken>()
    val listCineClubes by injection<ListCineClubes>()
    val getCineClube by injection<GetCineClube>()
    val checkSlug by injection<CheckCineClubeSlug>()
    val createCineClube by injection<CreateCineClube>()
    val updateCineClube by injection<UpdateCineClube>()
    val cancelCineClube by injection<CancelCineClube>()
    val joinCineClube by injection<JoinCineClube>()
    val leaveCineClube by injection<LeaveCineClube>()
    val getProgress by injection<GetCineClubeProgress>()
    val getParticipants by injection<GetCineClubeParticipants>()

    get("/cineclubes") {
        val mine = call.flag("mine")
        val participant = call.parameters["participant"]
        val viewerId = if (mine || participant == "me") {
            call.authenticateJWT(validateToken)?.claims?.userId ?: return@get
        } else {
            call.tryAuthenticateJWT(validateToken)?.claims?.userId
        }
        val page = call.pageRequest() ?: return@get
        val filter = CineClubeFilter(
            query = call.parameters["q"],
            open = call.flag("open"),
            includeFinished = call.flag("includeFinished"),
            mine = mine,
            participant = if (participant == "me") viewerId else participant
        )
        call.respond(listCineClubes(viewerId, filter, page))
    }

    get("/cineclubes/slug-available") {
        call.respond(checkSlug(call.parameters["slug"].orEmpty()))
    }

    post("/cineclubes") {
        val principal = call.authenticateJWT(validateToken) ?: return@post
        val input = call.receive<CineClubeInput>()
        call.respond(HttpStatusCode.Created, createCineClube(principal.claims.userId, input))
    }

    get("/cineclubes/{slug}") {
        val viewerId = call.tryAuthenticateJWT(validateToken)?.claims?.userId
        call.respond(getCineClube(viewerId, call.slug))
    }

    put("/cineclubes/{slug}") {
        val principal = call.authenticateJWT(validateToken) ?: return@put
        val input = call.receive<CineClubeInput>()
        call.respond(updateCineClube(principal.claims.userId, call.slug, input))
    }

    post("/cineclubes/{slug}/cancel") {
        val principal = call.authenticateJWT(validateToken) ?: return@post
        call.respond(cancelCineClube(principal.claims.userId, call.slug))
    }

    post("/cineclubes/{slug}/participation") {
        val principal = call.authenticateJWT(validateToken) ?: return@post
        val request = call.receive<JoinCineClubeRequest>()
        call.respond(HttpStatusCode.Created, joinCineClube(principal.claims.userId, call.slug, request.password))
    }

    delete("/cineclubes/{slug}/participation") {
        val principal = call.authenticateJWT(validateToken) ?: return@delete
        leaveCineClube(principal.claims.userId, call.slug)
        call.respond(HttpStatusCode.NoContent)
    }

    get("/cineclubes/{slug}/progress") {
        val viewerId = call.tryAuthenticateJWT(validateToken)?.claims?.userId
        val sessionId = call.optionalSession() ?: return@get
        val page = call.pageRequest() ?: return@get
        call.respond(getProgress(viewerId, call.slug, sessionId.value, page))
    }

    get("/cineclubes/{slug}/participants") {
        val sessionId = call.optionalSession() ?: return@get
        val status = call.parameters["status"]
        val progress = status?.let {
            SessionProgress.fromWireValue(it) ?: run {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("validation_failed", "status must be pending, watching or completed"))
                return@get
            }
        }
        val page = call.pageRequest() ?: return@get
        call.respond(getParticipants(call.slug, sessionId.value, progress, call.flag("completedOnly"), page))
    }
}

/** The `session` query parameter: absent is the current session. Null when it was invalid (400 sent). */
@JvmInline
private value class SessionParameter(val value: Long?)

private suspend fun ApplicationCall.optionalSession(): SessionParameter? {
    val raw = parameters["session"] ?: return SessionParameter(null)
    val id = raw.toLongOrNull()?.takeIf { it > 0 }
    if (id == null) {
        respond(HttpStatusCode.BadRequest, ErrorResponse("validation_failed", "Invalid session"))
        return null
    }
    return SessionParameter(id)
}

private val ApplicationCall.slug: String get() = parameters["slug"]!!

private fun ApplicationCall.flag(name: String): Boolean = parameters[name]?.toBooleanStrictOrNull() ?: false
