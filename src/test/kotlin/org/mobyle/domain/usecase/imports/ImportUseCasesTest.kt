package org.mobyle.domain.usecase.imports

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.datetime.Instant
import org.mobyle.domain.model.ConflictException
import org.mobyle.domain.model.ImportBundle
import org.mobyle.domain.model.ImportFile
import org.mobyle.domain.model.ImportJob
import org.mobyle.domain.model.InvalidFileException
import org.mobyle.domain.model.ImportOptions
import org.mobyle.domain.model.ImportPreview
import org.mobyle.domain.model.ImportPreviewList
import org.mobyle.domain.model.ImportedMovie
import org.mobyle.domain.model.MovieCandidate
import org.mobyle.domain.model.NotFoundException
import org.mobyle.domain.model.UnmatchedItem
import org.mobyle.domain.model.ValidationException
import org.mobyle.domain.model.vocabulary.ImportSource
import org.mobyle.domain.model.vocabulary.ImportStatus
import org.mobyle.domain.model.vocabulary.Visibility
import org.mobyle.domain.repository.ImportFileReader
import org.mobyle.domain.repository.ImportJobQueue
import org.mobyle.domain.repository.ImportRepository
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ImportUseCasesTest {

    private val repository = mockk<ImportRepository>(relaxed = true)
    private val queue = mockk<ImportJobQueue>(relaxed = true)
    private val access = ImportAccess(repository)

    private fun job(status: ImportStatus, owner: String = "ana") = ImportJob(
        id = 7, source = ImportSource.FILMOW, status = status, inputRef = "ana", processed = 0, total = 0,
        preview = ImportPreview(1, 1, 0, 0, listOf(ImportPreviewList("L1", "Noir", 3))),
        options = null, counts = null, error = null,
        createdAt = Instant.fromEpochSeconds(0), updatedAt = Instant.fromEpochSeconds(0), finishedAt = null, userId = owner
    )

    @Test
    fun `starting reads the source with the session cookies`() {
        every { repository.createJob("ana", ImportSource.FILMOW, "ana_filmow") } returns job(ImportStatus.PENDING)

        StartImport(repository, queue)("ana", StartImportInput("filmow", " ana_filmow ", "session=1"))

        verify { queue.read(job(ImportStatus.PENDING), "session=1", false) }
    }

    @Test
    fun `starting rejects Letterboxd without a file, a bad username and a second active job`() {
        val start = StartImport(repository, queue)
        assertFailsWith<ValidationException> { start("ana", StartImportInput("letterboxd", "ana")) }
        assertFailsWith<ValidationException> { start("ana", StartImportInput("orkut", "ana")) }
        assertFailsWith<ValidationException> { start("ana", StartImportInput("filmow", "../etc")) }

        every { repository.createJob(any(), any(), any()) } returns null
        assertFailsWith<ConflictException> { start("ana", StartImportInput("filmow", "ana")) }
    }

    @Test
    fun `a file import reads the files first and starts on the preview, named after the file`() {
        val reader = mockk<ImportFileReader>()
        val files = listOf(ImportFile("C:\\Users\\ana\\maria-1.csv", ByteArray(0)), ImportFile("maria-2.csv", ByteArray(0)))
        val bundle = ImportBundle(watched = listOf(ImportedMovie("title:casablanca:1942", "Casablanca", year = 1942)))
        every { reader.read(ImportSource.FILMOW, files) } returns bundle
        every { repository.createJob("ana", ImportSource.FILMOW, "maria-1.csv") } returns job(ImportStatus.PENDING)
        every { repository.getJob(7) } returns job(ImportStatus.PREVIEW)

        StartFileImport(repository, reader)("ana", "filmow", files)

        verify { repository.transition(7, setOf(ImportStatus.PENDING), ImportStatus.READING, null) }
        verify { repository.saveRead(7, bundle) }
    }

    @Test
    fun `a file import with a bad file or source creates no job`() {
        val reader = mockk<ImportFileReader>()
        every { reader.read(any(), any()) } throws InvalidFileException("unsupported_file", "bad")
        val start = StartFileImport(repository, reader)

        assertFailsWith<InvalidFileException> { start("ana", "letterboxd", listOf(ImportFile("x.csv", ByteArray(0)))) }
        assertFailsWith<ValidationException> { start("ana", "orkut", listOf(ImportFile("x.csv", ByteArray(0)))) }
        assertFailsWith<ValidationException> { start("ana", null, listOf(ImportFile("x.csv", ByteArray(0)))) }
        verify(exactly = 0) { repository.createJob(any(), any(), any()) }
    }

    @Test
    fun `jobs of someone else are not found`() {
        every { repository.getJob(7) } returns job(ImportStatus.PREVIEW, owner = "bia")

        assertFailsWith<NotFoundException> { GetImport(access)("ana", 7) }
    }

    @Test
    fun `confirming checks the lists against the preview and starts writing`() {
        every { repository.getJob(7) } returns job(ImportStatus.PREVIEW)
        every { repository.confirm(7, any()) } returns true
        val confirm = ConfirmImport(repository, access, queue)

        assertFailsWith<ValidationException> { confirm("ana", 7, ConfirmImportInput(listOf("L2"))) }
        assertFailsWith<ValidationException> { confirm("ana", 7, ConfirmImportInput(defaultVisibility = "friends")) }

        confirm("ana", 7, ConfirmImportInput(listOf("L1"), "secret"))

        verify { repository.confirm(7, ImportOptions(listOf("L1"), Visibility.SECRET)) }
        verify { queue.write(any()) }
    }

    @Test
    fun `confirming outside the preview conflicts`() {
        every { repository.getJob(7) } returns job(ImportStatus.READING)

        assertFailsWith<ConflictException> { ConfirmImport(repository, access, queue)("ana", 7, ConfirmImportInput()) }
    }

    @Test
    fun `cancelling is only allowed before writing`() {
        every { repository.getJob(7) } returns job(ImportStatus.IMPORTING)
        assertFailsWith<ConflictException> { CancelImport(repository, access)("ana", 7) }

        every { repository.getJob(7) } returns job(ImportStatus.PREVIEW)
        every { repository.transition(7, any(), ImportStatus.CANCELLED) } returns true
        CancelImport(repository, access)("ana", 7)
        verify { repository.transition(7, setOf(ImportStatus.PENDING, ImportStatus.READING, ImportStatus.PREVIEW), ImportStatus.CANCELLED) }
    }

    @Test
    fun `resolving an unmatched item uses the candidate title, skips, and refuses twice`() {
        val item = UnmatchedItem(3, 7, ImportedMovie("f-4", "Filme Raro"), listOf(MovieCandidate(300, "Rare Movie", score = 0.8)), "pending", 40)
        every { repository.getJob(7) } returns job(ImportStatus.DONE)
        every { repository.getUnmatchedItem(3) } returns item
        val resolve = ResolveUnmatchedItem(repository, access)

        resolve("ana", 7, 3, ResolveUnmatchedInput(tmdbId = 300))
        verify { repository.matchUnmatched(item, ImportSource.FILMOW, 300, "Rare Movie") }

        assertFailsWith<ValidationException> { resolve("ana", 7, 3, ResolveUnmatchedInput(tmdbId = 999)) }
        resolve("ana", 7, 3, ResolveUnmatchedInput(skip = true))
        verify { repository.skipUnmatched(3) }

        every { repository.getUnmatchedItem(3) } returns item.copy(resolution = "skipped")
        assertFailsWith<ConflictException> { resolve("ana", 7, 3, ResolveUnmatchedInput(skip = true)) }
        every { repository.getUnmatchedItem(3) } returns item.copy(jobId = 8)
        assertFailsWith<NotFoundException> { resolve("ana", 7, 3, ResolveUnmatchedInput(skip = true)) }
    }
}
