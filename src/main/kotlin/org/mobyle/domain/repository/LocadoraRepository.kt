package org.mobyle.domain.repository

import org.mobyle.domain.model.Locadora
import org.mobyle.domain.model.LocadoraDraft
import org.mobyle.domain.model.LocadoraShelf
import org.mobyle.domain.model.Page
import org.mobyle.domain.model.PageRequest
import org.mobyle.domain.model.vocabulary.Visibility

/**
 * Locadoras and the shelves they group. Users are identified by their external id.
 * [shelfVisibilities] limits the owner's shelves to the ones the viewer may see,
 * in the listings and in the counters.
 */
interface LocadoraRepository {
    fun getLocadoraOwnerId(locadoraId: Long): String?
    fun getLocadora(locadoraId: Long, shelfVisibilities: Set<Visibility>): Locadora?
    /** Locadoras of [ownerId] with one of [visibilities]; the same set filters their shelves. */
    fun getUserLocadoras(ownerId: String, visibilities: Set<Visibility>, page: PageRequest): Page<Locadora>
    fun createLocadora(ownerId: String, draft: LocadoraDraft): Locadora
    fun updateLocadora(locadoraId: Long, draft: LocadoraDraft): Locadora
    /** Deletes the locadora only; its shelves stay. */
    fun deleteLocadora(locadoraId: Long)

    fun getLocadoraShelves(
        locadoraId: Long,
        shelfVisibilities: Set<Visibility>,
        page: PageRequest,
        moviesPerShelf: Int
    ): Page<LocadoraShelf>
    fun locadoraShelfIds(locadoraId: Long): List<Long>
    /** Replaces the set of shelves. Shelves already in keep their order; new ones go to the end. */
    fun setShelves(locadoraId: Long, shelfIds: List<Long>)
    /** [shelfIds] first, in this order; the rest keep their relative order after them. */
    fun reorderShelves(locadoraId: Long, shelfIds: List<Long>)
    /** Profile order: [locadoraIds] first, in this order. When [pinnedIds] is given, it replaces the pinned set. */
    fun reorderLocadoras(ownerId: String, locadoraIds: List<Long>, pinnedIds: List<Long>?)
}
