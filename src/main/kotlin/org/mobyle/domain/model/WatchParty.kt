package org.mobyle.domain.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * A watch party ("Sessão sincronizada", F10.4): friends press play together at [startAt],
 * a time of the server, and talk in the chat. Rooms live in memory and expire. [serverTime]
 * lets the app correct its clock for the countdown.
 */
@Serializable
data class WatchParty(
    val code: String,
    val host: UserSummary,
    val title: String,
    val movie: MovieCard?,
    val startAt: Instant?,
    val members: List<UserSummary>,
    val messages: List<PartyMessage>,
    val createdAt: Instant,
    val serverTime: Instant,
    val self: Boolean = false
)

@Serializable
data class PartyMessage(val id: Long, val user: UserSummary, val body: String, val sentAt: Instant)

/** A round of the poster game (F10.5): guess the movie of the poster among the options. */
@Serializable
data class PosterRound(val movie: MovieCard, val options: List<MovieCard>)
