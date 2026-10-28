package org.mobyle.domain.usecase.shelves

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.datetime.Instant
import org.mobyle.domain.model.ForbiddenException
import org.mobyle.domain.model.NotFoundException
import org.mobyle.domain.model.PageRequest
import org.mobyle.domain.model.Shelf
import org.mobyle.domain.model.ShelfDraft
import org.mobyle.domain.model.ShelfTransferMode
import org.mobyle.domain.model.ValidationException
import org.mobyle.domain.model.vocabulary.ShelfSort
import org.mobyle.domain.model.vocabulary.SystemShelfType
import org.mobyle.domain.model.vocabulary.Visibility
import org.mobyle.domain.repository.ShelfRepository
import org.mobyle.domain.usecase.feed.ActivityPublisher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ShelfUseCasesTest {

    private val repository = mockk<ShelfRepository>(relaxed = true)
    private val activities = mockk<ActivityPublisher>(relaxed = true)
    private val access = ShelfAccess(repository)

    private fun shelf(
        id: Long,
        owner: String = "ana",
        visibility: Visibility = Visibility.PUBLIC,
        systemType: SystemShelfType? = null,
        name: String = "Shelf $id"
    ) = Shelf(
        id = id, ownerId = owner, name = name, description = null, color = null, visibility = visibility,
        systemType = systemType, sortMode = ShelfSort.MANUAL, position = 0, pinned = false, coverUrl = null,
        totalMovies = 0, totalWatched = 0, posterPaths = emptyList(),
        createdAt = Instant.fromEpochSeconds(0), updatedAt = Instant.fromEpochSeconds(0)
    )

    @Test
    fun `system shelves keep their name but the rest is editable`() {
        val watched = shelf(1, systemType = SystemShelfType.WATCHED, name = "Já assisti")
        every { repository.getShelf(1) } returns watched
        every { repository.updateShelf(1, any()) } answers { watched.copy(color = secondArg<ShelfDraft>().color) }
        val updateShelf = UpdateShelf(repository, access, activities)

        val error = assertFailsWith<ForbiddenException> { updateShelf("ana", 1, "Vistos", null, null, "public") }
        assertEquals("system_shelf_rename", error.code)

        val updated = updateShelf("ana", 1, "Já assisti", "Meus filmes", "#112233", "secret")
        assertEquals("#112233", updated.color)
        verify { repository.updateShelf(1, ShelfDraft("Já assisti", "Meus filmes", "#112233", Visibility.SECRET)) }
    }

    @Test
    fun `system shelves cannot be deleted, regular ones can`() {
        every { repository.getShelf(1) } returns shelf(1, systemType = SystemShelfType.WATCHLIST)
        every { repository.getShelf(2) } returns shelf(2)
        val deleteShelf = DeleteShelf(repository, access, activities)

        assertEquals("system_shelf_delete", assertFailsWith<ForbiddenException> { deleteShelf("ana", 1) }.code)
        deleteShelf("ana", 2)
        verify(exactly = 1) { repository.deleteShelf(2) }
    }

    @Test
    fun `a shelf the viewer cannot see does not exist`() {
        every { repository.getShelf(1) } returns shelf(1, visibility = Visibility.SECRET)
        every { repository.getShelf(2) } returns shelf(2, visibility = Visibility.MUTUAL)
        every { repository.areMutuals("bia", "ana") } returns true
        every { repository.areMutuals("caio", "ana") } returns false

        assertFailsWith<NotFoundException> { access.visibleShelf("bia", 1) }
        assertFailsWith<NotFoundException> { access.visibleShelf(null, 2) }
        assertFailsWith<NotFoundException> { access.visibleShelf("caio", 2) }
        assertEquals(false, access.visibleShelf("bia", 2).self)
        assertEquals(true, access.visibleShelf("ana", 1).self)
    }

    @Test
    fun `only the owner changes a visible shelf`() {
        every { repository.getShelf(1) } returns shelf(1)

        val error = assertFailsWith<ForbiddenException> { RemoveMovieFromShelf(repository, access)("bia", 1, 10) }
        assertEquals("not_shelf_owner", error.code)
    }

    @Test
    fun `user shelves are filtered by the viewer relation`() {
        every { repository.userExists("ana") } returns true
        every { repository.areMutuals("bia", "ana") } returns true
        val getUserShelves = GetUserShelves(repository, access)

        getUserShelves("bia", "ana", PageRequest())
        getUserShelves(null, "ana", PageRequest())

        verify { repository.ensureSystemShelves("ana") }
        verify { repository.getUserShelves("ana", setOf(Visibility.PUBLIC, Visibility.MUTUAL), PageRequest(), true) }
        verify { repository.getUserShelves("ana", setOf(Visibility.PUBLIC), PageRequest(), true) }
    }

    @Test
    fun `the owner sees the shelves a locadora hides only outside the profile`() {
        every { repository.userExists("ana") } returns true
        val getUserShelves = GetUserShelves(repository, access)

        getUserShelves("ana", "ana", PageRequest())
        getUserShelves("ana", "ana", PageRequest(), profile = true)

        verify { repository.getUserShelves("ana", Visibility.entries.toSet(), PageRequest(), false) }
        verify { repository.getUserShelves("ana", Visibility.entries.toSet(), PageRequest(), true) }
    }

    @Test
    fun `unknown user has no shelves`() {
        every { repository.userExists("ghost") } returns false

        assertFailsWith<NotFoundException> { GetUserShelves(repository, access)(null, "ghost", PageRequest()) }
    }

    @Test
    fun `shelf input is validated`() {
        assertEquals("name", assertFailsWith<ValidationException> { shelfDraftOf("  ", null, null, null) }.field)
        assertEquals("name", assertFailsWith<ValidationException> { shelfDraftOf("x".repeat(151), null, null, null) }.field)
        assertEquals("description", assertFailsWith<ValidationException> { shelfDraftOf("ok", "d".repeat(151), null, null) }.field)
        assertEquals("color", assertFailsWith<ValidationException> { shelfDraftOf("ok", null, "red", null) }.field)
        assertEquals("visibility", assertFailsWith<ValidationException> { shelfDraftOf("ok", null, null, "private") }.field)

        assertEquals(ShelfDraft("Cult", null, "#AABBCC", Visibility.PUBLIC), shelfDraftOf(" Cult ", "  ", "#AABBCC", null))
    }

    @Test
    fun `adding a movie to several shelves checks every shelf first`() {
        every { repository.getShelf(1) } returns shelf(1)
        every { repository.getShelf(2) } returns shelf(2, owner = "bia")
        every { repository.resolveMovieId(null, 100, "Alien") } returns 11

        assertFailsWith<ForbiddenException> { AddMovieToShelves(repository, access)("ana", listOf(1, 2), null, 100, "Alien") }
        verify(exactly = 0) { repository.addMovie(any(), any()) }

        every { repository.getShelf(2) } returns shelf(2)
        assertEquals(11L, AddMovieToShelves(repository, access)("ana", listOf(1, 2, 2), null, 100, "Alien"))
        verify(exactly = 2) { repository.addMovie(any(), 11) }
    }

    @Test
    fun `transfer needs another shelf and sort needs a known mode`() {
        every { repository.getShelf(any()) } answers { shelf(firstArg()) }

        assertFailsWith<ValidationException> { TransferShelfMovies(repository, access)("ana", 1, 1, ShelfTransferMode.MOVE, emptyList()) }
        assertFailsWith<ValidationException> { SortShelf(repository, access)("ana", 1, "random", false, null) }

        SortShelf(repository, access)("ana", 1, null, false, listOf(3, 2))
        verify { repository.sortShelf(1, ShelfSort.MANUAL, false) }
        verify { repository.reorderMovies(1, listOf(3, 2)) }
        assertTrue(true)
    }
}
