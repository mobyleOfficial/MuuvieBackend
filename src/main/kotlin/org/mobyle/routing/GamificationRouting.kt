package org.mobyle.routing

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import org.mobyle.data.remote.auth.ErrorResponse
import org.mobyle.data.remote.auth.authenticateJWT
import org.mobyle.di.injection
import org.mobyle.domain.usecase.auth.ValidateToken
import org.mobyle.domain.usecase.gamification.ChooseStarterPet
import org.mobyle.domain.usecase.gamification.DiaryDayInput
import org.mobyle.domain.usecase.gamification.GetDiaryDay
import org.mobyle.domain.usecase.gamification.GetDiaryMonth
import org.mobyle.domain.usecase.gamification.GetDiaryYear
import org.mobyle.domain.usecase.gamification.GetPetShelter
import org.mobyle.domain.usecase.gamification.PinPet
import org.mobyle.domain.usecase.gamification.SaveDiaryDay
import org.mobyle.domain.usecase.gamification.WeeklyMissions
import org.mobyle.domain.usecase.users.ProfileAccess

@Serializable
data class StarterRequest(val slug: String = "")

/**
 * Bichinhos, missions and the Caderninho of the v1 API (B10). Everything belongs to the
 * current user, except the public list of the bichinhos of a profile.
 */
fun Route.getGamificationRouting() {
    val validateToken by injection<ValidateToken>()
    val getShelter by injection<GetPetShelter>()
    val chooseStarter by injection<ChooseStarterPet>()
    val pinPet by injection<PinPet>()
    val missions by injection<WeeklyMissions>()
    val getDiaryDay by injection<GetDiaryDay>()
    val saveDiaryDay by injection<SaveDiaryDay>()
    val getDiaryMonth by injection<GetDiaryMonth>()
    val getDiaryYear by injection<GetDiaryYear>()
    val profiles by injection<ProfileAccess>()

    get("/users/me/pets") {
        val principal = call.authenticateJWT(validateToken) ?: return@get
        call.respond(getShelter(principal.claims.userId))
    }

    post("/users/me/pets/starter") {
        val principal = call.authenticateJWT(validateToken) ?: return@post
        val request = call.receive<StarterRequest>()
        call.respond(HttpStatusCode.Created, chooseStarter(principal.claims.userId, request.slug))
    }

    put("/users/me/pets/{slug}/pin") {
        val principal = call.authenticateJWT(validateToken) ?: return@put
        call.respond(pinPet(principal.claims.userId, call.parameters["slug"]!!))
    }

    get("/users/{userId}/pets") {
        call.respond(getShelter.public(profiles.resolveUserId(call.parameters["userId"]!!)))
    }

    get("/users/me/missions") {
        val principal = call.authenticateJWT(validateToken) ?: return@get
        call.respond(missions(principal.claims.userId))
    }

    get("/users/me/diary") {
        val principal = call.authenticateJWT(validateToken) ?: return@get
        val month = call.parameters["month"]?.let { Regex("^(\\d{4})-(\\d{2})$").matchEntire(it) }
        if (month == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("validation_failed", "month must be YYYY-MM"))
            return@get
        }
        call.respond(getDiaryMonth(principal.claims.userId, month.groupValues[1].toInt(), month.groupValues[2].toInt()))
    }

    get("/users/me/diary/summary/{year}") {
        val principal = call.authenticateJWT(validateToken) ?: return@get
        val year = call.parameters["year"]?.toIntOrNull()?.takeIf { it in 1900..3000 }
        if (year == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("validation_failed", "Invalid year"))
            return@get
        }
        call.respond(getDiaryYear(principal.claims.userId, year))
    }

    get("/users/me/diary/{date}") {
        val principal = call.authenticateJWT(validateToken) ?: return@get
        val date = call.date() ?: return@get
        call.respond(getDiaryDay(principal.claims.userId, date))
    }

    put("/users/me/diary/{date}") {
        val principal = call.authenticateJWT(validateToken) ?: return@put
        val date = call.date() ?: return@put
        val input = call.receive<DiaryDayInput>()
        call.respond(saveDiaryDay(principal.claims.userId, date, input))
    }
}

private suspend fun ApplicationCall.date(): LocalDate? {
    val date = parameters["date"]?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    if (date == null) respond(HttpStatusCode.BadRequest, ErrorResponse("validation_failed", "date must be YYYY-MM-DD"))
    return date
}
