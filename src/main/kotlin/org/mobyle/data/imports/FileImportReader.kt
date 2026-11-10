package org.mobyle.data.imports

import org.mobyle.domain.model.ImportBundle
import org.mobyle.domain.model.ImportFile
import org.mobyle.domain.model.InvalidFileException
import org.mobyle.domain.model.PayloadTooLargeException
import org.mobyle.domain.model.vocabulary.ImportSource
import org.mobyle.domain.repository.ImportFileReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * Reads the files of an import (research/features/csv-import/spec.md):
 *
 * - Filmow: one or more CSVs from filmow_to_letterboxd, in Letterboxd's import format.
 * - Letterboxd: the export zip, CSVs taken out of it, or CSVs in the import format.
 *
 * Everything happens in memory; the bytes are never stored. The limits guard the server
 * against huge or malicious uploads (a zip bomb), far above any real profile.
 */
class FileImportReader(private val limits: Limits = Limits()) : ImportFileReader {

    data class Limits(
        val maxFiles: Int = 10,
        val maxZipEntries: Int = 2_000,
        val maxUncompressedBytes: Long = 50L * 1024 * 1024,
        val maxRows: Int = 50_000
    )

    override fun read(source: ImportSource, files: List<ImportFile>): ImportBundle {
        if (files.isEmpty()) throw InvalidFileException(EMPTY_FILE, "No file was sent")
        if (files.size > limits.maxFiles) {
            throw PayloadTooLargeException(FILE_TOO_LARGE, "Send at most ${limits.maxFiles} files")
        }
        val csvFiles = files.flatMap { expand(source, it) }
        if (csvFiles.sumOf { it.dataRowCount } > limits.maxRows) {
            throw PayloadTooLargeException(FILE_TOO_LARGE, "The files have more than ${limits.maxRows} rows")
        }
        val classified = csvFiles.mapNotNull { file -> classify(source, file)?.let { it to file } }
        val bundle = ImportBundleBuilder(acceptLetterboxdUris = source == ImportSource.LETTERBOXD).build(classified)
        if (bundle.distinctMovies.isEmpty()) throw InvalidFileException(EMPTY_FILE, "No movies were found in the files")
        return bundle
    }

    /** A zip becomes the CSVs of the export it holds; anything else is read as one CSV. */
    private fun expand(source: ImportSource, file: ImportFile): List<ImportCsvFile> {
        if (!isZip(file.bytes)) return listOf(ImportCsvFile(file.name, Csv.parse(file.bytes.decodeToString())))
        if (source != ImportSource.LETTERBOXD) {
            throw InvalidFileException(UNSUPPORTED_FILE, "${file.name}: Filmow imports take the CSV from filmow_to_letterboxd, not a zip")
        }
        val entries = readZip(file).filter { (path, _) -> isExportFile(path) }
        if (entries.isEmpty()) {
            throw InvalidFileException(UNSUPPORTED_FILE, "${file.name}: this zip is not a Letterboxd export")
        }
        return entries.map { (path, bytes) -> ImportCsvFile(path, Csv.parse(bytes.decodeToString()), fromZip = true) }
    }

    private fun readZip(file: ImportFile): List<Pair<String, ByteArray>> {
        val entries = mutableListOf<Pair<String, ByteArray>>()
        var entryCount = 0
        var uncompressedBytes = 0L
        try {
            ZipInputStream(ByteArrayInputStream(file.bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (++entryCount > limits.maxZipEntries) {
                        throw PayloadTooLargeException(FILE_TOO_LARGE, "${file.name}: the zip has too many files")
                    }
                    if (entry.isDirectory || !isExportFile(entry.name)) continue
                    // Counted while reading: the sizes a zip declares can lie.
                    val buffer = ByteArrayOutputStream()
                    val chunk = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val read = zip.read(chunk)
                        if (read < 0) break
                        uncompressedBytes += read
                        if (uncompressedBytes > limits.maxUncompressedBytes) {
                            throw PayloadTooLargeException(FILE_TOO_LARGE, "${file.name}: the zip is too large once extracted")
                        }
                        buffer.write(chunk, 0, read)
                    }
                    entries.add(entry.name to buffer.toByteArray())
                }
            }
        } catch (e: IOException) {
            // ZipException and a truncated stream (EOFException) alike.
            throw InvalidFileException(UNSUPPORTED_FILE, "${file.name}: the zip could not be opened")
        }
        return entries
    }

    /**
     * The files of the export the import uses. Ignored: profile, reviews, comments, liked lists
     * and reviews, and the deleted/ and orphaned/ folders.
     */
    private fun isExportFile(entryPath: String): Boolean {
        val path = entryPath.replace('\\', '/').lowercase()
        val segments = path.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty() || !path.endsWith(".csv")) return false
        if (segments.any { it == "deleted" || it == "orphaned" || it == "__macosx" }) return false
        val name = segments.last()
        val folder = segments.getOrNull(segments.size - 2)
        return when (folder) {
            "lists" -> true
            "likes" -> name == "films.csv"
            else -> name in TOP_LEVEL_FILES
        }
    }

    /** What the CSV holds; null for a blank file inside a zip, which is skipped. */
    private fun classify(source: ImportSource, file: ImportCsvFile): ImportCsvKind? {
        if (file.isBlank()) {
            if (file.fromZip) return null
            throw InvalidFileException(EMPTY_FILE, "${file.fileName}: the file is empty")
        }
        val kind = kindOf(file)
        if (source == ImportSource.FILMOW && kind != ImportCsvKind.IMPORT_FORMAT) {
            throw InvalidFileException(
                UNSUPPORTED_FILE,
                "${file.fileName}: this is a Letterboxd export file; choose Letterboxd as the origin"
            )
        }
        return kind
    }

    private fun kindOf(file: ImportCsvFile): ImportCsvKind {
        val firstCell = file.rows.first().firstOrNull()?.trim()?.lowercase().orEmpty()
        if (firstCell.startsWith(LIST_PREAMBLE)) return ImportCsvKind.LIST
        val header = file.rows.first().map(Csv::normalizeHeader).toSet()
        val isExport = "name" in header && "letterboxduri" in header
        return when {
            isExport && "watcheddate" in header -> ImportCsvKind.DIARY
            isExport && "rating" in header -> ImportCsvKind.RATINGS
            isExport -> when (file.fileName.lowercase()) {
                "watched.csv" -> ImportCsvKind.WATCHED
                "watchlist.csv" -> ImportCsvKind.WATCHLIST
                "films.csv" -> ImportCsvKind.LIKES
                // Watched, watchlist and likes share one header; guessing could mark unseen movies as seen.
                else -> throw InvalidFileException(
                    AMBIGUOUS_FILE,
                    "${file.fileName}: rename it to watched.csv, watchlist.csv or films.csv, or send the export zip"
                )
            }
            header.any { it in IMPORT_FORMAT_KEYS } -> ImportCsvKind.IMPORT_FORMAT
            else -> throw InvalidFileException(UNSUPPORTED_FILE, "${file.fileName}: the columns were not recognised")
        }
    }

    private fun isZip(bytes: ByteArray): Boolean =
        bytes.size >= ZIP_MAGIC.size && ZIP_MAGIC.indices.all { bytes[it] == ZIP_MAGIC[it] }

    companion object {
        const val EMPTY_FILE = "empty_file"
        const val UNSUPPORTED_FILE = "unsupported_file"
        const val AMBIGUOUS_FILE = "ambiguous_file"
        const val FILE_TOO_LARGE = "file_too_large"

        private const val LIST_PREAMBLE = "letterboxd list export"
        private const val BUFFER_SIZE = 8 * 1024
        private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
        private val TOP_LEVEL_FILES = setOf("watched.csv", "ratings.csv", "diary.csv", "watchlist.csv")
        private val IMPORT_FORMAT_KEYS = setOf("title", "imdbid", "tmdbid", "letterboxduri")
    }
}
