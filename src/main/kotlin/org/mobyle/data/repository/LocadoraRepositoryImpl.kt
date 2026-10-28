package org.mobyle.data.repository

import org.mobyle.data.local.locadoras.LocadoraDataSource
import org.mobyle.domain.model.Locadora
import org.mobyle.domain.model.LocadoraDraft
import org.mobyle.domain.model.LocadoraShelf
import org.mobyle.domain.model.Page
import org.mobyle.domain.model.PageRequest
import org.mobyle.domain.model.vocabulary.Visibility
import org.mobyle.domain.repository.LocadoraRepository

class LocadoraRepositoryImpl(private val locadoraDataSource: LocadoraDataSource) : LocadoraRepository {
    override fun getLocadoraOwnerId(locadoraId: Long): String? = locadoraDataSource.getLocadoraOwnerId(locadoraId)

    override fun getLocadora(locadoraId: Long, shelfVisibilities: Set<Visibility>): Locadora? =
        locadoraDataSource.getLocadora(locadoraId, shelfVisibilities)

    override fun getUserLocadoras(ownerId: String, visibilities: Set<Visibility>, page: PageRequest): Page<Locadora> =
        locadoraDataSource.getUserLocadoras(ownerId, visibilities, page)

    override fun createLocadora(ownerId: String, draft: LocadoraDraft): Locadora =
        locadoraDataSource.createLocadora(ownerId, draft)

    override fun updateLocadora(locadoraId: Long, draft: LocadoraDraft): Locadora =
        locadoraDataSource.updateLocadora(locadoraId, draft)

    override fun deleteLocadora(locadoraId: Long) = locadoraDataSource.deleteLocadora(locadoraId)

    override fun getLocadoraShelves(
        locadoraId: Long,
        shelfVisibilities: Set<Visibility>,
        page: PageRequest,
        moviesPerShelf: Int
    ): Page<LocadoraShelf> = locadoraDataSource.getLocadoraShelves(locadoraId, shelfVisibilities, page, moviesPerShelf)

    override fun locadoraShelfIds(locadoraId: Long): List<Long> = locadoraDataSource.locadoraShelfIds(locadoraId)

    override fun setShelves(locadoraId: Long, shelfIds: List<Long>) = locadoraDataSource.setShelves(locadoraId, shelfIds)

    override fun reorderShelves(locadoraId: Long, shelfIds: List<Long>) =
        locadoraDataSource.reorderShelves(locadoraId, shelfIds)

    override fun reorderLocadoras(ownerId: String, locadoraIds: List<Long>, pinnedIds: List<Long>?) =
        locadoraDataSource.reorderLocadoras(ownerId, locadoraIds, pinnedIds)
}
