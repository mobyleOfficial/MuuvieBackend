package org.mobyle.routing

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import org.mobyle.di.injection
import org.mobyle.domain.usecase.movies.GetPersonDetail

fun Route.getPersonsRouting() {
    val getPersonDetail by injection<GetPersonDetail>()

    get("/persons/{id}") {
        val personId = call.parameters["id"]?.toLongOrNull()
        if (personId == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid person ID"))
            return@get
        }
        val detail = getPersonDetail(personId)
        if (detail == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Person not found"))
        } else {
            call.respond(detail)
        }
    }
}
