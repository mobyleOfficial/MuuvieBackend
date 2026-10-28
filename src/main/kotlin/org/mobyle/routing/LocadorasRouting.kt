package org.mobyle.routing

import io.ktor.http.HttpStatusCode
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
import org.mobyle.domain.usecase.auth.ValidateToken
import org.mobyle.domain.usecase.locadoras.CreateLocadora
import org.mobyle.domain.usecase.locadoras.DeleteLocadora
import org.mobyle.domain.usecase.locadoras.GetLocadora
import org.mobyle.domain.usecase.locadoras.GetLocadoraShelves
import org.mobyle.domain.usecase.locadoras.GetUserLocadoras
import org.mobyle.domain.usecase.locadoras.ReorderLocadoraShelves
import org.mobyle.domain.usecase.locadoras.ReorderLocadoras
import org.mobyle.domain.usecase.locadoras.SetLocadoraShelves
import org.mobyle.domain.usecase.locadoras.UpdateLocadora

@Serializable
data class LocadoraRequest(
    val name: String,
    val description: String? = null,
    val visibility: String? = null,
    val hideShelvesInProfile: Boolean = false,
    val hideShelvesInFilters: Boolean = false
)

@Serializable
data class LocadoraShelvesRequest(val shelfIds: List<Long> = emptyList())

@Serializable
data class ReorderLocadorasRequest(val locadoraIds: List<Long> = emptyList(), val pinnedIds: List<Long>? = null)

/** Locadoras of the v1 API (B4). Only mounted under /api/v1; the legacy /profile/locadoras routes stay until B11. */
fun Route.getLocadorasRouting() {
    val validateToken by injection<ValidateToken>()
    val getUserLocadoras by injection<GetUserLocadoras>()
    val getLocadora by injection<GetLocadora>()
    val createLocadora by injection<CreateLocadora>()
    val updateLocadora by injection<UpdateLocadora>()
    val deleteLocadora by injection<DeleteLocadora>()
    val getLocadoraShelves by injection<GetLocadoraShelves>()
    val setLocadoraShelves by injection<SetLocadoraShelves>()
    val reorderLocadoraShelves by injection<ReorderLocadoraShelves>()
    val reorderLocadoras by injection<ReorderLocadoras>()

    get("/users/{userId}/locadoras") {
        val userId = call.parameters["userId"]!!
        val viewerId = if (userId == "me") {
            call.authenticateJWT(validateToken)?.claims?.userId ?: return@get
        } else {
            call.tryAuthenticateJWT(validateToken)?.claims?.userId
        }
        val page = call.pageRequest() ?: return@get
        call.respond(getUserLocadoras(viewerId, if (userId == "me") viewerId!! else userId, page))
    }

    put("/users/me/locadoras/order") {
        val principal = call.authenticateJWT(validateToken) ?: return@put
        val request = call.receive<ReorderLocadorasRequest>()
        reorderLocadoras(principal.claims.userId, request.locadoraIds, request.pinnedIds)
        call.respond(HttpStatusCode.NoContent)
    }

    post("/locadoras") {
        val principal = call.authenticateJWT(validateToken) ?: return@post
        val request = call.receive<LocadoraRequest>()
        val locadora = createLocadora(
            principal.claims.userId,
            request.name,
            request.description,
            request.visibility,
            request.hideShelvesInProfile,
            request.hideShelvesInFilters
        )
        call.respond(HttpStatusCode.Created, locadora)
    }

    get("/locadoras/{id}") {
        val locadoraId = call.longParameter("id") ?: return@get
        val viewerId = call.tryAuthenticateJWT(validateToken)?.claims?.userId
        call.respond(getLocadora(viewerId, locadoraId))
    }

    put("/locadoras/{id}") {
        val principal = call.authenticateJWT(validateToken) ?: return@put
        val locadoraId = call.longParameter("id") ?: return@put
        val request = call.receive<LocadoraRequest>()
        call.respond(
            updateLocadora(
                principal.claims.userId,
                locadoraId,
                request.name,
                request.description,
                request.visibility,
                request.hideShelvesInProfile,
                request.hideShelvesInFilters
            )
        )
    }

    delete("/locadoras/{id}") {
        val principal = call.authenticateJWT(validateToken) ?: return@delete
        val locadoraId = call.longParameter("id") ?: return@delete
        deleteLocadora(principal.claims.userId, locadoraId)
        call.respond(HttpStatusCode.NoContent)
    }

    get("/locadoras/{id}/shelves") {
        val locadoraId = call.longParameter("id") ?: return@get
        val viewerId = call.tryAuthenticateJWT(validateToken)?.claims?.userId
        val page = call.pageRequest() ?: return@get
        call.respond(getLocadoraShelves(viewerId, locadoraId, page))
    }

    put("/locadoras/{id}/shelves") {
        val principal = call.authenticateJWT(validateToken) ?: return@put
        val locadoraId = call.longParameter("id") ?: return@put
        val request = call.receive<LocadoraShelvesRequest>()
        setLocadoraShelves(principal.claims.userId, locadoraId, request.shelfIds)
        call.respond(HttpStatusCode.NoContent)
    }

    put("/locadoras/{id}/shelves/order") {
        val principal = call.authenticateJWT(validateToken) ?: return@put
        val locadoraId = call.longParameter("id") ?: return@put
        val request = call.receive<LocadoraShelvesRequest>()
        reorderLocadoraShelves(principal.claims.userId, locadoraId, request.shelfIds)
        call.respond(HttpStatusCode.NoContent)
    }
}
