package org.mobyle.data.imports

import kotlinx.datetime.LocalDate
import org.mobyle.domain.model.ImportFile
import org.mobyle.domain.model.InvalidFileException
import org.mobyle.domain.model.PayloadTooLargeException
import org.mobyle.domain.model.vocabulary.ImportSource
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileImportReaderTest {

    private val reader = FileImportReader()

    private fun csv(name: String, content: String) = ImportFile(name, content.toByteArray())

    private fun zip(name: String, entries: Map<String, String>): ImportFile {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (path, content) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return ImportFile(name, output.toByteArray())
    }

    private fun fixture(name: String) =
        ImportFile(name, FileImportReaderTest::class.java.getResourceAsStream("/imports/$name")!!.readBytes())

    // ── Filmow (filmow_to_letterboxd) ──────────────────────────────────────

    @Test
    fun `reads the CSV filmow_to_letterboxd writes into watched movies`() {
        val bundle = reader.read(ImportSource.FILMOW, listOf(fixture("filmow_to_letterboxd.csv")))

        assertEquals(4, bundle.watched.size)
        val cidade = bundle.watched.first()
        assertEquals("title:cidade de deus:2002", cidade.externalId)
        assertEquals("Cidade de Deus", cidade.title)
        assertEquals("Fernando Meirelles, Kátia Lund", cidade.director)
        assertEquals(5.0, cidade.stars)
        assertNull(bundle.watched.single { it.title == "Amélie" }.stars)
        assertEquals(2000, bundle.watched.single { it.title == "Crouching Tiger, Hidden Dragon" }.year)
        assertTrue(bundle.watchlist.isEmpty() && bundle.favorites.isEmpty() && bundle.lists.isEmpty())
    }

    @Test
    fun `the files of a large Filmow profile are merged, without repeating a movie`() {
        val first = csv("maria-1.csv", "Title,Directors,Year,Rating\nCasablanca,Michael Curtiz,1942,4.5\n")
        val second = csv("maria-2.csv", "Title,Directors,Year,Rating\nVertigo,Alfred Hitchcock,1958,5\nCasablanca,Michael Curtiz,1942,\n")

        val bundle = reader.read(ImportSource.FILMOW, listOf(first, second))

        assertEquals(listOf("Casablanca", "Vertigo"), bundle.watched.map { it.title })
        assertEquals(4.5, bundle.watched.first().stars)
    }

    @Test
    fun `Filmow refuses a zip and the files of a Letterboxd export`() {
        val export = zip("export.zip", mapOf("watched.csv" to "Date,Name,Year,Letterboxd URI\n"))
        val watched = csv("watched.csv", "Date,Name,Year,Letterboxd URI\n2024-01-01,Vertigo,1958,https://boxd.it/2a\n")

        assertEquals("unsupported_file", assertFailsWith<InvalidFileException> { reader.read(ImportSource.FILMOW, listOf(export)) }.code)
        assertEquals("unsupported_file", assertFailsWith<InvalidFileException> { reader.read(ImportSource.FILMOW, listOf(watched)) }.code)
    }

    // ── Letterboxd import format ───────────────────────────────────────────

    @Test
    fun `the import format takes ids, Rating10, dates and keeps the latest watch of a movie`() {
        val file = csv(
            "mine.csv",
            "Title,Year,tmdbID,imdbID,Rating10,WatchedDate,LetterboxdURI\n" +
                "Vertigo,1958,426,,9,2023-05-01,https://letterboxd.com/film/vertigo/\n" +
                "Vertigo,1958,426,,,2024-02-03,https://letterboxd.com/film/vertigo/\n" +
                ",,,tt0034583,,,\n"
        )

        val bundle = reader.read(ImportSource.LETTERBOXD, listOf(file))

        val vertigo = bundle.watched.first()
        assertEquals("https://letterboxd.com/film/vertigo/", vertigo.externalId)
        assertEquals(426, vertigo.tmdbId)
        assertEquals("tmdb", vertigo.matchMethod)
        assertEquals(4.5, vertigo.stars)
        assertEquals(LocalDate(2024, 2, 3), vertigo.watchedOn)
        assertEquals("https://www.imdb.com/title/tt0034583/", bundle.watched.last().imdbUrl)
        assertEquals(2, bundle.watched.size)
    }

    // ── Letterboxd export ──────────────────────────────────────────────────

    private val export = zip(
        "letterboxd-ana-2026-11-10-12-00-utc.zip",
        mapOf(
            "profile.csv" to "Date Joined,Username\n2020-01-01,ana\n",
            "watched.csv" to "Date,Name,Year,Letterboxd URI\n" +
                "2024-01-05,Past Lives,2023,https://boxd.it/aaaa\n" +
                "2024-01-06,\"Crouching Tiger, Hidden Dragon\",2000,https://boxd.it/bbbb\n",
            "ratings.csv" to "Date,Name,Year,Letterboxd URI,Rating\n" +
                "2024-01-05,Past Lives,2023,https://boxd.it/aaaa,4.5\n" +
                "2024-01-07,Aftersun,2022,https://boxd.it/cccc,5\n",
            "diary.csv" to "Date,Name,Year,Letterboxd URI,Rating,Rewatch,Tags,Watched Date\n" +
                "2024-01-05,Past Lives,2023,https://boxd.it/entry1,4.5,,,2023-12-30\n" +
                "2024-03-01,Past Lives,2023,https://boxd.it/entry2,4.5,Yes,,2024-02-28\n" +
                "2024-01-06,\"Crouching Tiger, Hidden Dragon\",2000,https://boxd.it/entry3,3,,,2024-01-06\n",
            "reviews.csv" to "Date,Name,Year,Letterboxd URI,Rating,Rewatch,Review,Tags,Watched Date\n" +
                "2024-01-05,Past Lives,2023,https://boxd.it/entry1,4.5,,\"First, wow\",,2023-12-30\n" +
                "2024-03-01,Past Lives,2023,https://boxd.it/entry2,4.5,Yes,Even better,,2024-02-28\n",
            "watchlist.csv" to "Date,Name,Year,Letterboxd URI\n2024-02-01,Perfect Days,2023,https://boxd.it/dddd\n",
            "likes/films.csv" to "Date,Name,Year,Letterboxd URI\n2024-01-05,Past Lives,2023,https://boxd.it/aaaa\n",
            "likes/lists.csv" to "Date,Name,Year,Letterboxd URI\n2024-01-05,Nope,2022,https://boxd.it/zzzz\n",
            "lists/best-of-2023.csv" to "Letterboxd list export v7\n" +
                "Date,Name,Tags,URL,Description\n" +
                "2024-01-02,Best of 2023,,https://letterboxd.com/ana/list/best-of-2023/,\"My favorites, ranked\"\n" +
                "\n" +
                "Position,Name,Year,URL,Description\n" +
                "2,Aftersun,2022,https://boxd.it/cccc,\n" +
                "1,Past Lives,2023,https://boxd.it/aaaa,\n",
            "deleted/watched.csv" to "Date,Name,Year,Letterboxd URI\n2024-01-01,Gone,2001,https://boxd.it/gone\n"
        )
    )

    @Test
    fun `reads the export zip into watched, watchlist, favorites and lists`() {
        val bundle = reader.read(ImportSource.LETTERBOXD, listOf(export))

        assertEquals(listOf("Past Lives", "Crouching Tiger, Hidden Dragon", "Aftersun"), bundle.watched.map { it.title })
        assertEquals(listOf("https://boxd.it/dddd"), bundle.watchlist.map { it.externalId })
        assertEquals(listOf("https://boxd.it/aaaa"), bundle.favorites.map { it.externalId })
        val list = bundle.lists.single()
        assertEquals("https://letterboxd.com/ana/list/best-of-2023/", list.externalId)
        assertEquals("Best of 2023", list.title)
        assertEquals("My favorites, ranked", list.description)
        assertEquals(listOf("Past Lives", "Aftersun"), list.movies.map { it.title })
    }

    @Test
    fun `ratings rate, the diary dates by title and year, and Date is never the watch date`() {
        val watched = reader.read(ImportSource.LETTERBOXD, listOf(export)).watched.associateBy { it.title }

        val pastLives = watched.getValue("Past Lives")
        assertEquals("https://boxd.it/aaaa", pastLives.externalId)
        assertEquals(4.5, pastLives.stars)
        assertEquals(LocalDate(2024, 2, 28), pastLives.watchedOn)
        // Every diary entry is a session, the rewatch included.
        assertEquals(listOf(LocalDate(2023, 12, 30), LocalDate(2024, 2, 28)), pastLives.sessionDates)
        // Rated only: still watched, with no date.
        assertEquals(5.0, watched.getValue("Aftersun").stars)
        assertNull(watched.getValue("Aftersun").watchedOn)
        // No rating file row: the diary rates it.
        assertEquals(3.0, watched.getValue("Crouching Tiger, Hidden Dragon").stars)
        // likes/lists.csv and deleted/ are ignored.
        assertTrue("Gone" !in watched && "Nope" !in watched)
    }

    @Test
    fun `two diary entries on one day are two sessions, and reviews add none`() {
        val diary = csv(
            "diary.csv",
            "Date,Name,Year,Letterboxd URI,Rating,Rewatch,Tags,Watched Date\n" +
                "2024-01-05,Vertigo,1958,https://boxd.it/e1,,,,2024-01-05\n" +
                "2024-01-05,Vertigo,1958,https://boxd.it/e2,,Yes,,2024-01-05\n"
        )
        val reviews = csv(
            "reviews.csv",
            "Date,Name,Year,Letterboxd URI,Rating,Rewatch,Review,Tags,Watched Date\n" +
                "2024-01-05,Vertigo,1958,https://boxd.it/e2,,Yes,De novo,,2024-01-05\n"
        )

        val vertigo = reader.read(ImportSource.LETTERBOXD, listOf(diary, reviews)).watched.single()

        assertEquals(listOf(LocalDate(2024, 1, 5), LocalDate(2024, 1, 5)), vertigo.sessionDates)
        assertEquals("De novo", vertigo.review)
    }

    @Test
    fun `tags become optional lists, left out unless chosen`() {
        val diary = csv(
            "diary.csv",
            "Date,Name,Year,Letterboxd URI,Rating,Rewatch,Tags,Watched Date\n" +
                "2024-01-05,Vertigo,1958,https://boxd.it/e1,,,\"cinema, Hitchcock\",2024-01-05\n" +
                "2024-02-05,Psycho,1960,https://boxd.it/e2,,,Cinema,2024-02-05\n"
        )

        val bundle = reader.read(ImportSource.LETTERBOXD, listOf(diary))

        val cinema = bundle.lists.single { it.externalId == "tag:cinema" }
        assertEquals("#cinema", cinema.title)
        assertTrue(cinema.optional)
        assertEquals(listOf("Vertigo", "Psycho"), cinema.movies.map { it.title })
        assertEquals(listOf("#cinema", "#Hitchcock"), bundle.lists.map { it.title })
        assertTrue(bundle.preview().lists.all { it.optional })
        assertTrue(bundle.withLists(null).lists.isEmpty())
        assertEquals(listOf("#Hitchcock"), bundle.withLists(setOf("tag:hitchcock")).lists.map { it.title })
    }

    @Test
    fun `reviews join their movie and the latest one wins`() {
        val watched = reader.read(ImportSource.LETTERBOXD, listOf(export)).watched.associateBy { it.title }

        assertEquals("Even better", watched.getValue("Past Lives").review)
        assertEquals(LocalDate(2024, 2, 28), watched.getValue("Past Lives").reviewOn)
        assertEquals(1, reader.read(ImportSource.LETTERBOXD, listOf(export)).preview().reviews)
    }

    @Test
    fun `the import format brings its Review column`() {
        val file = csv("mine.csv", "Title,Year,Rating,Review\nVertigo,1958,5,\"Obra-prima, revi\"\n")

        assertEquals("Obra-prima, revi", reader.read(ImportSource.FILMOW, listOf(file)).watched.single().review)
    }

    @Test
    fun `loose export files are told apart by header, then by file name`() {
        val watchlist = csv("watchlist.csv", "Date,Name,Year,Letterboxd URI\n2024-02-01,Perfect Days,2023,https://boxd.it/dddd\n")
        val diary = csv("my-diary.csv", "Date,Name,Year,Letterboxd URI,Rating,Rewatch,Tags,Watched Date\n2024-01-05,Past Lives,2023,https://boxd.it/e1,4,,,2023-12-30\n")

        val bundle = reader.read(ImportSource.LETTERBOXD, listOf(watchlist, diary))

        assertEquals("Perfect Days", bundle.watchlist.single().title)
        assertEquals(LocalDate(2023, 12, 30), bundle.watched.single().watchedOn)
        assertEquals("title:past lives:2023", bundle.watched.single().externalId)
    }

    @Test
    fun `a loose file whose header could be watched or watchlist must be named`() {
        val unnamed = csv("export (1).csv", "Date,Name,Year,Letterboxd URI\n2024-02-01,Perfect Days,2023,https://boxd.it/dddd\n")

        val error = assertFailsWith<InvalidFileException> { reader.read(ImportSource.LETTERBOXD, listOf(unnamed)) }
        assertEquals("ambiguous_file", error.code)
        assertTrue(error.message!!.contains("export (1).csv"))
    }

    // ── Bad input ──────────────────────────────────────────────────────────

    @Test
    fun `empty, unknown and broken files are refused`() {
        fun code(vararg files: ImportFile) =
            assertFailsWith<InvalidFileException> { reader.read(ImportSource.LETTERBOXD, files.toList()) }.code

        assertEquals("empty_file", code())
        assertEquals("empty_file", code(csv("a.csv", "")))
        assertEquals("empty_file", code(csv("a.csv", "Title,Year\n")))
        assertEquals("unsupported_file", code(csv("a.csv", "Filme,Ano\nVertigo,1958\n")))
        assertEquals("unsupported_file", code(zip("photos.zip", mapOf("a.jpg" to "x"))))
        assertEquals("unsupported_file", code(ImportFile("broken.zip", byteArrayOf(0x50, 0x4B, 0x03, 0x04, 1, 2, 3))))
    }

    @Test
    fun `too many files, rows or extracted bytes are refused`() {
        val small = FileImportReader(FileImportReader.Limits(maxFiles = 1, maxRows = 2, maxUncompressedBytes = 100))
        val oneRow = csv("a.csv", "Title\nVertigo\n")

        assertFailsWith<PayloadTooLargeException> { small.read(ImportSource.FILMOW, listOf(oneRow, oneRow)) }
        assertFailsWith<PayloadTooLargeException> { small.read(ImportSource.FILMOW, listOf(csv("a.csv", "Title\nA\nB\nC\n"))) }
        assertFailsWith<PayloadTooLargeException> {
            small.read(ImportSource.LETTERBOXD, listOf(zip("big.zip", mapOf("watched.csv" to "Date,Name,Year,Letterboxd URI\n" + "x".repeat(200)))))
        }
    }
}
