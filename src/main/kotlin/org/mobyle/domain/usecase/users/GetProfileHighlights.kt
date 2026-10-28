package org.mobyle.domain.usecase.users

import org.mobyle.domain.model.NotFoundException
import org.mobyle.domain.model.ProfileHighlight
import org.mobyle.domain.model.vocabulary.HighlightType
import org.mobyle.domain.repository.UserProfileRepository
import org.mobyle.domain.usecase.locadoras.LocadoraAccess
import org.mobyle.domain.usecase.shelves.ShelfAccess

/**
 * The Destaques of a profile (F5.4), resolved for the viewer. Others only get the enabled
 * sections whose shelf or locadora they can see; the owner gets every section, to edit them.
 */
class GetProfileHighlights(
    private val repository: UserProfileRepository,
    private val access: ProfileAccess,
    private val shelves: ShelfAccess,
    private val locadoras: LocadoraAccess
) {
    operator fun invoke(viewerId: String?, idOrHandle: String): List<ProfileHighlight> {
        val ownerId = access.resolveUserId(idOrHandle)
        val owner = viewerId == ownerId
        return repository.getHighlights(ownerId)
            .filter { owner || it.enabled }
            .mapNotNull { highlight -> resolve(viewerId, highlight).takeIf { owner || it.isComplete() } }
    }

    private fun resolve(viewerId: String?, highlight: ProfileHighlight): ProfileHighlight = when (highlight.type) {
        HighlightType.TOP4 -> highlight.copy(movies = repository.movieCards(highlight.movieIds))
        HighlightType.SHELF -> highlight.copy(
            shelf = highlight.shelfId?.let { orNull { shelves.visibleShelf(viewerId, it) } }
        )
        HighlightType.LOCADORA -> highlight.copy(
            locadora = highlight.locadoraId?.let { orNull { locadoras.visibleLocadora(viewerId, it) } }
        )
        HighlightType.SOUNDTRACK -> highlight
    }

    private fun ProfileHighlight.isComplete(): Boolean = when (type) {
        HighlightType.TOP4 -> movies.isNotEmpty()
        HighlightType.SHELF -> shelf != null
        HighlightType.LOCADORA -> locadora != null
        HighlightType.SOUNDTRACK -> soundtrackUrl != null
    }

    private fun <T> orNull(block: () -> T): T? = try {
        block()
    } catch (_: NotFoundException) {
        null
    }
}
