package org.mobyle.routing

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import org.mobyle.data.remote.auth.authenticateJWT
import org.mobyle.di.injection
import org.mobyle.domain.usecase.auth.ValidateToken
import org.mobyle.domain.usecase.imports.CancelImport
import org.mobyle.domain.usecase.imports.ConfirmImport
import org.mobyle.domain.usecase.imports.ConfirmImportInput
import org.mobyle.domain.usecase.imports.GetImport
import org.mobyle.domain.usecase.imports.GetImports
import org.mobyle.domain.usecase.imports.GetUnmatchedItems
import org.mobyle.domain.usecase.imports.ResolveUnmatchedItem
import org.mobyle.domain.usecase.imports.ResolveUnmatchedInput
import org.mobyle.domain.usecase.imports.StartImport
import org.mobyle.domain.usecase.imports.StartImportInput

/** Import jobs (phase 3, B3). Only mounted under /api/v1; every route is the owner's. */
fun Route.getImportsRouting() {
    val validateToken by injection<ValidateToken>()
    val startImport by injection<StartImport>()
    val getImport by injection<GetImport>()
    val getImports by injection<GetImports>()
    val confirmImport by injection<ConfirmImport>()
    val cancelImport by injection<CancelImport>()
    val getUnmatchedItems by injection<GetUnmatchedItems>()
    val resolveUnmatchedItem by injection<ResolveUnmatchedItem>()

    post("/imports") {
        val principal = call.authenticateJWT(validateToken) ?: return@post
        val input = call.receive<StartImportInput>()
        call.respond(HttpStatusCode.Accepted, startImport(principal.claims.userId, input))
    }

    get("/imports") {
        val principal = call.authenticateJWT(validateToken) ?: return@get
        val page = call.pageRequest() ?: return@get
        call.respond(getImports(principal.claims.userId, page))
    }

    get("/imports/{id}") {
        val principal = call.authenticateJWT(validateToken) ?: return@get
        val jobId = call.longParameter("id") ?: return@get
        call.respond(getImport(principal.claims.userId, jobId))
    }

    post("/imports/{id}/confirm") {
        val principal = call.authenticateJWT(validateToken) ?: return@post
        val jobId = call.longParameter("id") ?: return@post
        val input = call.receive<ConfirmImportInput>()
        call.respond(HttpStatusCode.Accepted, confirmImport(principal.claims.userId, jobId, input))
    }

    post("/imports/{id}/cancel") {
        val principal = call.authenticateJWT(validateToken) ?: return@post
        val jobId = call.longParameter("id") ?: return@post
        call.respond(cancelImport(principal.claims.userId, jobId))
    }

    get("/imports/{id}/unmatched") {
        val principal = call.authenticateJWT(validateToken) ?: return@get
        val jobId = call.longParameter("id") ?: return@get
        val page = call.pageRequest() ?: return@get
        call.respond(getUnmatchedItems(principal.claims.userId, jobId, page))
    }

    post("/imports/{id}/unmatched/{itemId}") {
        val principal = call.authenticateJWT(validateToken) ?: return@post
        val jobId = call.longParameter("id") ?: return@post
        val itemId = call.longParameter("itemId") ?: return@post
        val input = call.receive<ResolveUnmatchedInput>()
        call.respond(resolveUnmatchedItem(principal.claims.userId, jobId, itemId, input))
    }
}
