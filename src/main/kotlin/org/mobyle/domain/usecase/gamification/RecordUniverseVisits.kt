package org.mobyle.domain.usecase.gamification

import kotlinx.serialization.Serializable
import org.mobyle.domain.model.ValidationException
import org.mobyle.domain.repository.GamificationRepository

/**
 * How many star systems the user opened in the Universo de Filmes, and the explorer bichinhos
 * this visit unlocked, if any.
 */
@Serializable
data class UniverseVisits(val systemsVisited: Int, val unlockedPets: List<String> = emptyList())

/**
 * Records the star systems the user opened in the Universo de Filmes and unlocks the explorer
 * bichinhos ("foguete", "astronauta") they reached (research/features/movie-universe/roadmap.md,
 * phase 5). The app sends each new system once, but sending one again is harmless.
 */
class RecordUniverseVisits(
    private val repository: GamificationRepository,
    private val unlocker: PetUnlocker
) {
    operator fun invoke(userId: String, movieIds: List<Long>): UniverseVisits {
        if (movieIds.isEmpty() || movieIds.size > MAX_PER_CALL) {
            throw ValidationException("movieIds", "Send from 1 to $MAX_PER_CALL movie ids")
        }
        if (movieIds.any { it <= 0 }) throw ValidationException("movieIds", "Movie ids must be positive")
        val total = repository.addUniverseVisits(userId, movieIds)
        return UniverseVisits(systemsVisited = total, unlockedPets = unlocker.check(userId))
    }

    companion object {
        const val MAX_PER_CALL = 50
    }
}
