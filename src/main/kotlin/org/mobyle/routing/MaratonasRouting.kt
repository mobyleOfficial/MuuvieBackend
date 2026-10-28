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
import org.mobyle.data.remote.auth.authenticateJWT
import org.mobyle.data.remote.auth.tryAuthenticateJWT
import org.mobyle.di.injection
import org.mobyle.domain.model.MaratonaFilter
import org.mobyle.domain.model.Page
import org.mobyle.domain.model.PageRequest
import org.mobyle.domain.usecase.auth.ValidateToken
import org.mobyle.domain.usecase.maratonas.CancelMaratona
import org.mobyle.domain.usecase.maratonas.CheckMaratonaSlug
import org.mobyle.domain.usecase.maratonas.CreateMaratona
import org.mobyle.domain.usecase.maratonas.CreateMaratonaFromTemplate
import org.mobyle.domain.usecase.maratonas.FromTemplateInput
import org.mobyle.domain.usecase.maratonas.GetChallengeSuggestions
import org.mobyle.domain.usecase.maratonas.GetMaratona
import org.mobyle.domain.usecase.maratonas.GetMaratonaChallenges
import org.mobyle.domain.usecase.maratonas.GetMaratonaProgress
import org.mobyle.domain.usecase.maratonas.GetMaratonaRanking
import org.mobyle.domain.usecase.maratonas.GetMaratonaTemplates
import org.mobyle.domain.usecase.maratonas.JoinMaratona
import org.mobyle.domain.usecase.maratonas.LeaveMaratona
import org.mobyle.domain.usecase.maratonas.ListMaratonas
import org.mobyle.domain.usecase.maratonas.MaratonaInput
import org.mobyle.domain.usecase.maratonas.SetChallengeLink
import org.mobyle.domain.usecase.maratonas.SetMaratonaCoOwners
import org.mobyle.domain.usecase.maratonas.UpdateMaratona

@Serializable
data class JoinMaratonaRequest(val levelId: Long? = null, val teamId: Long? = null, val password: String? = null)

@Serializable
data class CoOwnersRequest(val userIds: List<String> = emptyList())

/** Maratonas of the v1 API (B7). Listing, detail and challenges work without login. */
fun Route.getMaratonasRouting() {
    val validateToken by injection<ValidateToken>()
    val listMaratonas by injection<ListMaratonas>()
    val getMaratona by injection<GetMaratona>()
    val checkSlug by injection<CheckMaratonaSlug>()
    val createMaratona by injection<CreateMaratona>()
    val updateMaratona by injection<UpdateMaratona>()
    val cancelMaratona by injection<CancelMaratona>()
    val setCoOwners by injection<SetMaratonaCoOwners>()
    val joinMaratona by injection<JoinMaratona>()
    val leaveMaratona by injection<LeaveMaratona>()
    val getChallenges by injection<GetMaratonaChallenges>()
    val getSuggestions by injection<GetChallengeSuggestions>()
    val setChallengeLink by injection<SetChallengeLink>()
    val getRanking by injection<GetMaratonaRanking>()
    val getProgress by injection<GetMaratonaProgress>()
    val getTemplates by injection<GetMaratonaTemplates>()
    val createFromTemplate by injection<CreateMaratonaFromTemplate>()

    get("/maratonas") {
        val mine = call.flag("mine")
        val participant = call.parameters["participant"]
        val viewerId = if (mine || participant == "me") {
            call.authenticateJWT(validateToken)?.claims?.userId ?: return@get
        } else {
            call.tryAuthenticateJWT(validateToken)?.claims?.userId
        }
        val page = call.pageRequest() ?: return@get
        val filter = MaratonaFilter(
            query = call.parameters["q"],
            open = call.flag("open"),
            includeFinished = call.flag("includeFinished"),
            trustedOnly = call.flag("trusted"),
            mine = mine,
            participant = if (participant == "me") viewerId else participant
        )
        call.respond(listMaratonas(viewerId, filter, page))
    }

    get("/maratonas/slug-available") {
        call.respond(checkSlug(call.parameters["slug"].orEmpty()))
    }

    post("/maratonas") {
        val principal = call.authenticateJWT(validateToken) ?: return@post
        val input = call.receive<MaratonaInput>()
        call.respond(HttpStatusCode.Created, createMaratona(principal.claims.userId, input))
    }

    get("/maratona-templates") {
        call.respond(getTemplates())
    }

    post("/maratonas/from-template/{templateId}") {
        val principal = call.authenticateJWT(validateToken) ?: return@post
        val input = call.receive<FromTemplateInput>()
        call.respond(HttpStatusCode.Created, createFromTemplate(principal.claims.userId, call.parameters["templateId"]!!, input))
    }

    get("/maratonas/{slug}") {
        val viewerId = call.tryAuthenticateJWT(validateToken)?.claims?.userId
        call.respond(getMaratona(viewerId, call.slug))
    }

    put("/maratonas/{slug}") {
        val principal = call.authenticateJWT(validateToken) ?: return@put
        val input = call.receive<MaratonaInput>()
        call.respond(updateMaratona(principal.claims.userId, call.slug, input))
    }

    post("/maratonas/{slug}/cancel") {
        val principal = call.authenticateJWT(validateToken) ?: return@post
        call.respond(cancelMaratona(principal.claims.userId, call.slug))
    }

    put("/maratonas/{slug}/owners") {
        val principal = call.authenticateJWT(validateToken) ?: return@put
        val request = call.receive<CoOwnersRequest>()
        call.respond(setCoOwners(principal.claims.userId, call.slug, request.userIds))
    }

    post("/maratonas/{slug}/participation") {
        val principal = call.authenticateJWT(validateToken) ?: return@post
        val request = call.receive<JoinMaratonaRequest>()
        call.respond(
            HttpStatusCode.Created,
            joinMaratona(principal.claims.userId, call.slug, request.levelId, request.teamId, request.password)
        )
    }

    delete("/maratonas/{slug}/participation") {
        val principal = call.authenticateJWT(validateToken) ?: return@delete
        leaveMaratona(principal.claims.userId, call.slug)
        call.respond(HttpStatusCode.NoContent)
    }

    get("/maratonas/{slug}/challenges") {
        val viewerId = call.tryAuthenticateJWT(validateToken)?.claims?.userId
        val teamOnly = call.parameters["teamOnly"]?.toBooleanStrictOrNull()
        call.respond(getChallenges(viewerId, call.slug, teamOnly))
    }

    get("/maratonas/{slug}/challenges/{id}/suggestions") {
        val challengeId = call.longParameter("id") ?: return@get
        val page = call.parameters["page"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        call.respond(getSuggestions(call.slug, challengeId, page))
    }

    put("/maratonas/{slug}/challenges/{id}/links/{movieId}") {
        val principal = call.authenticateJWT(validateToken) ?: return@put
        val challengeId = call.longParameter("id") ?: return@put
        val movieId = call.longParameter("movieId") ?: return@put
        call.respond(setChallengeLink(principal.claims.userId, call.slug, challengeId, movieId, linked = true))
    }

    delete("/maratonas/{slug}/challenges/{id}/links/{movieId}") {
        val principal = call.authenticateJWT(validateToken) ?: return@delete
        val challengeId = call.longParameter("id") ?: return@delete
        val movieId = call.longParameter("movieId") ?: return@delete
        call.respond(setChallengeLink(principal.claims.userId, call.slug, challengeId, movieId, linked = false))
    }

    get("/maratonas/{slug}/ranking") {
        when (call.parameters["by"] ?: "user") {
            "user" -> {
                val page = call.pageRequest() ?: return@get
                call.respond(getRanking.users(call.slug, page))
            }
            "team" -> {
                val teams = getRanking.teams(call.slug)
                call.respond(Page.of(teams, PageRequest(1, PageRequest.MAX_LIMIT), teams.size))
            }
            else -> call.respond(HttpStatusCode.BadRequest, ErrorResponse("validation_failed", "by must be user or team"))
        }
    }

    get("/maratonas/{slug}/progress") {
        val viewerId = call.tryAuthenticateJWT(validateToken)?.claims?.userId
        call.respond(getProgress(viewerId, call.slug, call.parameters["cursor"]))
    }
}

private val ApplicationCall.slug: String get() = parameters["slug"]!!

private fun ApplicationCall.flag(name: String): Boolean = parameters[name]?.toBooleanStrictOrNull() ?: false
