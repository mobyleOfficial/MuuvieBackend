package org.mobyle.data.repository

import org.mobyle.data.local.imports.ImportDataSource
import org.mobyle.domain.model.ImportBundle
import org.mobyle.domain.model.ImportCounts
import org.mobyle.domain.model.ImportJob
import org.mobyle.domain.model.ImportOptions
import org.mobyle.domain.model.MovieCandidate
import org.mobyle.domain.model.Page
import org.mobyle.domain.model.PageRequest
import org.mobyle.domain.model.UnmatchedItem
import org.mobyle.domain.model.vocabulary.ImportSource
import org.mobyle.domain.model.vocabulary.ImportStatus
import org.mobyle.domain.repository.ImportRepository

class ImportRepositoryImpl(private val importDataSource: ImportDataSource) : ImportRepository {
    override fun createJob(userId: String, source: ImportSource, inputRef: String): ImportJob? =
        importDataSource.createJob(userId, source, inputRef)

    override fun getJob(jobId: Long): ImportJob? = importDataSource.getJob(jobId)

    override fun getJobs(userId: String, page: PageRequest): Page<ImportJob> = importDataSource.getJobs(userId, page)

    override fun hasRunningJob(userId: String): Boolean = importDataSource.hasRunningJob(userId)

    override fun transition(jobId: Long, from: Set<ImportStatus>, status: ImportStatus, error: String?): Boolean =
        importDataSource.transition(jobId, from, status, error)

    override fun saveRead(jobId: Long, bundle: ImportBundle): Boolean = importDataSource.saveRead(jobId, bundle)

    override fun getBundle(jobId: Long): ImportBundle? = importDataSource.getBundle(jobId)

    override fun confirm(jobId: Long, options: ImportOptions): Boolean = importDataSource.confirm(jobId, options)

    override fun updateProgress(jobId: Long, processed: Int, total: Int) =
        importDataSource.updateProgress(jobId, processed, total)

    override fun write(
        job: ImportJob,
        bundle: ImportBundle,
        options: ImportOptions,
        unmatched: Map<String, List<MovieCandidate>>,
        onProgress: (processed: Int, total: Int) -> Unit
    ): ImportCounts = importDataSource.write(job, bundle, options, unmatched, onProgress)

    override fun finish(jobId: Long, counts: ImportCounts) = importDataSource.finish(jobId, counts)

    override fun failInterrupted(): Int = importDataSource.failInterrupted()

    override fun knownTmdbId(source: ImportSource, externalId: String): Int? =
        importDataSource.knownTmdbId(source, externalId)

    override fun getUnmatched(jobId: Long, page: PageRequest): Page<UnmatchedItem> =
        importDataSource.getUnmatched(jobId, page)

    override fun getUnmatchedItem(itemId: Long): UnmatchedItem? = importDataSource.getUnmatchedItem(itemId)

    override fun matchUnmatched(item: UnmatchedItem, source: ImportSource, tmdbId: Int, title: String): UnmatchedItem =
        importDataSource.matchUnmatched(item, source, tmdbId, title)

    override fun skipUnmatched(itemId: Long): UnmatchedItem = importDataSource.skipUnmatched(itemId)
}
