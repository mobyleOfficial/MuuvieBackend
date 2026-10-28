package org.mobyle.domain.usecase.users

import org.mobyle.domain.model.ProfileHighlight
import org.mobyle.domain.model.ValidationException
import org.mobyle.domain.model.vocabulary.HighlightType
import org.mobyle.domain.repository.UserProfileRepository
import org.mobyle.domain.usecase.locadoras.LocadoraAccess
import org.mobyle.domain.usecase.feed.ActivityPublisher
import org.mobyle.domain.usecase.shelves.ShelfAccess

/**
 * Replaces the Destaques of the own profile (F5.4): the list order is the order on the profile.
 * The featured shelf and locadora must be the user's own; the top 4 must be known movies.
 */
class SaveProfileHighlights(
    private val repository: UserProfileRepository,
    private val shelves: ShelfAccess,
    private val locadoras: LocadoraAccess,
    private val getHighlights: GetProfileHighlights,
    private val activities: ActivityPublisher
) {
    operator fun invoke(userId: String, inputs: List<HighlightInput>): List<ProfileHighlight> {
        val drafts = highlightDraftsOf(inputs)
        drafts.forEach { draft ->
            when (draft.type) {
                HighlightType.TOP4 -> if (repository.movieCards(draft.movieIds).size != draft.movieIds.size) {
                    throw ValidationException("movieIds", "Unknown movie in the top 4")
                }
                HighlightType.SHELF -> shelves.ownedShelf(userId, draft.shelfId!!)
                HighlightType.LOCADORA -> locadoras.ownedLocadora(userId, draft.locadoraId!!)
                HighlightType.SOUNDTRACK -> Unit
            }
        }
        val featuredBefore = repository.getHighlights(userId).featuredShelfId()
        repository.saveHighlights(userId, drafts)
        // A newly featured shelf is news for the feed (estante_destaque).
        val featured = drafts.firstOrNull { it.type == HighlightType.SHELF && it.enabled }?.shelfId
        if (featured != null && featured != featuredBefore) activities.shelfFeatured(shelves.ownedShelf(userId, featured))
        return getHighlights(userId, userId)
    }
}

private fun List<ProfileHighlight>.featuredShelfId(): Long? =
    firstOrNull { it.type == HighlightType.SHELF && it.enabled }?.shelfId
