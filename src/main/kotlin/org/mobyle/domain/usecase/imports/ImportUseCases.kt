package org.mobyle.domain.usecase.imports

import kotlinx.serialization.Serializable
import org.mobyle.domain.model.ConflictException
import org.mobyle.domain.model.ImportJob
import org.mobyle.domain.model.ImportOptions
import org.mobyle.domain.model.NotFoundException
import org.mobyle.domain.model.Page
import org.mobyle.domain.model.PageRequest
import org.mobyle.domain.model.UnavailableException
import org.mobyle.domain.model.ValidationException
import org.mobyle.domain.model.vocabulary.ImportSource
import org.mobyle.domain.model.vocabulary.ImportStatus
import org.mobyle.domain.model.vocabulary.Visibility
import org.mobyle.domain.repository.ImportJobQueue
import org.mobyle.domain.repository.ImportRepository

private val USERNAME = Regex("^[A-Za-z0-9_.-]{1,100}$")

/** POST /imports. [cookies] is the Filmow session from the app login; it is never stored. */
@Serializable
data class StartImportInput(val source: String, val username: String = "", val cookies: String = "")

/** POST /imports/{id}/confirm. */
@Serializable
data class ConfirmImportInput(val listsToImport: List<String>? = null, val defaultVisibility: String? = null)

/** Jobs are only visible to their owner; anyone else gets a 404. */
class ImportAccess(private val repository: ImportRepository) {
    fun ownedJob(userId: String, jobId: Long): ImportJob =
        repository.getJob(jobId)?.takeIf { it.userId == userId } ?: throw NotFoundException("Import not found")
}

/** F3.1: creates the job and starts reading the source. One active job per user and source. */
class StartImport(private val repository: ImportRepository, private val queue: ImportJobQueue) {
    operator fun invoke(userId: String, input: StartImportInput, autoConfirm: Boolean = false): ImportJob {
        val source = ImportSource.fromWireValue(input.source)
            ?: throw ValidationException("source", "source must be one of ${ImportSource.entries.joinToString { it.wireValue }}")
        // F3.3 is on hold until the Letterboxd input format is decided (roadmap A1).
        if (source == ImportSource.LETTERBOXD) {
            throw UnavailableException("import_source_unavailable", "Letterboxd import is not available yet")
        }
        val username = input.username.trim()
        if (!USERNAME.matches(username)) throw ValidationException("username", "username is invalid")
        val job = repository.createJob(userId, source, username)
            ?: throw ConflictException("import_in_progress", "An import from this source is already in progress")
        queue.read(job, input.cookies, autoConfirm)
        return job
    }
}

class GetImport(private val access: ImportAccess) {
    operator fun invoke(userId: String, jobId: Long): ImportJob = access.ownedJob(userId, jobId)
}

/** History of the user's imports, newest first (F3.6). */
class GetImports(private val repository: ImportRepository) {
    operator fun invoke(userId: String, page: PageRequest): Page<ImportJob> = repository.getJobs(userId, page)
}

/** F3.5: the user accepted the preview; writing starts in the background. */
class ConfirmImport(
    private val repository: ImportRepository,
    private val access: ImportAccess,
    private val queue: ImportJobQueue
) {
    operator fun invoke(userId: String, jobId: Long, input: ConfirmImportInput): ImportJob {
        val job = access.ownedJob(userId, jobId)
        val preview = job.preview
        if (job.status != ImportStatus.PREVIEW || preview == null) {
            throw ConflictException("import_not_in_preview", "The import is not waiting for confirmation")
        }
        val known = preview.lists.map { it.externalId }.toSet()
        val lists = input.listsToImport?.distinct()
        if (lists != null && !known.containsAll(lists)) {
            throw ValidationException("listsToImport", "listsToImport has lists that are not in the preview")
        }
        val visibility = input.defaultVisibility?.let {
            Visibility.fromWireValue(it)
                ?: throw ValidationException("defaultVisibility", "defaultVisibility must be public, mutual or secret")
        } ?: Visibility.DEFAULT
        if (!repository.confirm(jobId, ImportOptions(lists, visibility))) {
            throw ConflictException("import_not_in_preview", "The import is not waiting for confirmation")
        }
        val confirmed = repository.getJob(jobId)!!
        queue.write(confirmed)
        return confirmed
    }
}

/** Cancels before anything is written (F3.5): reading or waiting on the preview. */
class CancelImport(private val repository: ImportRepository, private val access: ImportAccess) {
    operator fun invoke(userId: String, jobId: Long): ImportJob {
        val job = access.ownedJob(userId, jobId)
        val cancellable = ImportStatus.entries.filter { it.isCancellable }.toSet()
        if (!job.status.isCancellable || !repository.transition(jobId, cancellable, ImportStatus.CANCELLED)) {
            throw ConflictException("import_not_cancellable", "The import can no longer be cancelled")
        }
        return repository.getJob(jobId)!!
    }
}
