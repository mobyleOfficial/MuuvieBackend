package org.mobyle.domain.repository

import org.mobyle.domain.model.ImportBundle
import org.mobyle.domain.model.ImportCounts
import org.mobyle.domain.model.ImportJob
import org.mobyle.domain.model.ImportOptions
import org.mobyle.domain.model.ImportedMovie
import org.mobyle.domain.model.MovieCandidate
import org.mobyle.domain.model.Page
import org.mobyle.domain.model.PageRequest
import org.mobyle.domain.model.UnmatchedItem
import org.mobyle.domain.model.vocabulary.ImportSource
import org.mobyle.domain.model.vocabulary.ImportStatus

/**
 * Import jobs and the writer that applies a bundle (B3). Users are identified by their external id.
 *
 * Writing is idempotent: movies are matched by TMDB id or source id, lists by
 * (source, external id) and logs by (movie, date, source). Data created in the Muuvie is
 * never overwritten, and a list edited here only gains movies.
 */
interface ImportRepository {
    /** Creates a pending job; null when the user already has an active job for the source. */
    fun createJob(userId: String, source: ImportSource, inputRef: String): ImportJob?
    fun getJob(jobId: Long): ImportJob?
    fun getJobs(userId: String, page: PageRequest): Page<ImportJob>
    /** Reading or writing; a job waiting on its preview is not running. */
    fun hasRunningJob(userId: String): Boolean

    /** Moves the job to [status] only if it is in one of [from]. Returns false otherwise. */
    fun transition(jobId: Long, from: Set<ImportStatus>, status: ImportStatus, error: String? = null): Boolean
    /** Stores what was read and its preview, moving the job from reading to preview. */
    fun saveRead(jobId: Long, bundle: ImportBundle): Boolean
    fun getBundle(jobId: Long): ImportBundle?
    /** Moves the job from preview to importing with the user's choices. */
    fun confirm(jobId: Long, options: ImportOptions): Boolean
    fun updateProgress(jobId: Long, processed: Int, total: Int)

    /**
     * Writes the bundle for the user. [unmatched] are the movies TMDB did not match with
     * confidence, with their candidates; they are imported anyway and queued for confirmation.
     */
    fun write(
        job: ImportJob,
        bundle: ImportBundle,
        options: ImportOptions,
        unmatched: Map<String, List<MovieCandidate>>,
        onProgress: (processed: Int, total: Int) -> Unit
    ): ImportCounts
    /** Ends the job as done, dropping the stored bundle. */
    fun finish(jobId: Long, counts: ImportCounts)
    /** Fails the jobs a restart interrupted while reading or importing. Returns how many. */
    fun failInterrupted(): Int

    /** The movie the catalog already knows for this source id, if it has a TMDB id. */
    fun knownTmdbId(source: ImportSource, externalId: String): Int?

    fun getUnmatched(jobId: Long, page: PageRequest): Page<UnmatchedItem>
    fun getUnmatchedItem(itemId: Long): UnmatchedItem?
    /** Points the imported movie to [tmdbId], merging it with the catalog movie. */
    fun matchUnmatched(item: UnmatchedItem, source: ImportSource, tmdbId: Int, title: String): UnmatchedItem
    fun skipUnmatched(itemId: Long): UnmatchedItem
}

/** Reads a source into a neutral bundle. [credentials] (e.g. session cookies) are never stored. */
interface ImportSourceReader {
    val source: ImportSource
    suspend fun read(inputRef: String, credentials: String): ImportBundle
}

/** Searches TMDB for an imported movie, best candidates first, scored by MovieMatchPolicy. */
interface MovieCandidateSearch {
    suspend fun candidates(movie: ImportedMovie): List<MovieCandidate>
}

/** Runs the slow steps of a job in the background and reports progress to the user. */
interface ImportJobQueue {
    /** With [autoConfirm], the preview is accepted as is (all lists) and writing follows. */
    fun read(job: ImportJob, credentials: String, autoConfirm: Boolean = false)
    fun write(job: ImportJob)
}
