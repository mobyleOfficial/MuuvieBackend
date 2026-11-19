package org.mobyle.data.remote.filmow

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.mobyle.domain.model.FilmowList
import org.mobyle.domain.model.FilmowMoviePartial
import org.mobyle.domain.model.FilmowProfile
import org.mobyle.domain.model.Movie
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class FilmowDataSourceImpl : FilmowDataSource {

    private val log = LoggerFactory.getLogger(FilmowDataSourceImpl::class.java)
    /** The Python scraper's own lines, under their own logger so they can be filtered in the console. */
    private val pyLog = LoggerFactory.getLogger("filmow.scraper.py")
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    companion object {
        private const val STDERR_TAIL_LINES = 40
        private const val QUICK_CHECK_SECONDS = 15L
        private const val SCRIPT_PATH = "scripts/filmow_scraper.py"
        private const val VENV_PYTHON_WIN = "scripts/.venv/Scripts/python.exe"
        private const val VENV_PYTHON_UNIX = "scripts/.venv/bin/python"
        private const val TIMEOUT_MINUTES = 20L
        private const val KNOWN_IDS_ENV = "FILMOW_KNOWN_IDS_FILE"
    }

    override suspend fun scrapeProfile(cookies: String, username: String, knownFilmowIds: Set<String>): FilmowProfile {
        val scriptFile = resolveScriptPath()
        if (!scriptFile.exists()) {
            throw IllegalStateException("Python scraper not found at: ${scriptFile.absolutePath}")
        }

        val pythonBin = resolveVenvPython()
        checkDependencies(pythonBin)

        val command = mutableListOf(pythonBin, scriptFile.absolutePath, username)
        if (cookies.isNotBlank()) {
            command.add(cookies)
        }

        log.info(
            "[SCRAPE] Starting for @$username — python=$pythonBin, script=${scriptFile.absolutePath}, " +
                "cookies=${cookieNames(cookies)} (${cookies.length} chars)"
        )
        // Passed as a file: thousands of ids do not fit in a command line.
        val knownIdsFile = knownFilmowIds.takeIf { it.isNotEmpty() }?.let { ids ->
            File.createTempFile("filmow-known-ids", ".txt").apply {
                deleteOnExit()
                writeText(ids.joinToString("\n"))
            }
        }
        log.info("[SCRAPE] Known Filmow ids passed to the scraper: ${knownFilmowIds.size}")

        val startTime = System.currentTimeMillis()

        val process = try {
            ProcessBuilder(command)
                .redirectErrorStream(false)
                .apply {
                    environment()["PYTHONUNBUFFERED"] = "1"
                    environment()["PYTHONIOENCODING"] = "utf-8"
                    knownIdsFile?.let { environment()[KNOWN_IDS_ENV] = it.absolutePath }
                }
                .start()
        } catch (e: Exception) {
            log.error("[SCRAPE] Could not start the scraper process ($pythonBin): ${e.message}", e)
            knownIdsFile?.delete()
            throw e
        }
        log.info("[SCRAPE] Scraper process started (pid=${process.pid()})")

        // The scraper reports progress on stderr; each line goes to the log (and so to the IDE console).
        val stderrTail = ArrayDeque<String>()
        val stderrThread = thread(isDaemon = true, name = "scraper-stderr") {
            process.errorStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    synchronized(stderrTail) {
                        stderrTail.addLast(line)
                        if (stderrTail.size > STDERR_TAIL_LINES) stderrTail.removeFirst()
                    }
                    logScraperLine(line)
                }
            }
        }

        val stdout = process.inputStream.bufferedReader(Charsets.UTF_8).readText()

        val exited = process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES)
        stderrThread.join(5000)
        knownIdsFile?.delete()
        if (!exited) {
            log.error("[SCRAPE] Timed out after $TIMEOUT_MINUTES min for @$username")
            process.destroyForcibly()
            throw RuntimeException("Filmow scraper timed out after $TIMEOUT_MINUTES minutes")
        }

        log.info("[SCRAPE] Scraper exited with ${process.exitValue()} in ${(System.currentTimeMillis() - startTime) / 1000}s")
        if (process.exitValue() != 0) {
            val tail = synchronized(stderrTail) { stderrTail.toList() }
            log.error(
                "[SCRAPE] Scraper failed for @$username (exit ${process.exitValue()}). stdout: ${stdout.take(1000).ifBlank { "<empty>" }}\n" +
                    "[SCRAPE] Last ${tail.size} stderr lines:\n" + tail.joinToString("\n") { "    $it" }
            )
            val reason = stdout.takeIf { it.isNotBlank() } ?: tail.lastOrNull() ?: "no output"
            throw RuntimeException("Filmow scraper failed (exit ${process.exitValue()}): $reason")
        }

        if (stdout.isBlank()) {
            log.error("[SCRAPE] Scraper exited 0 with empty stdout for @$username")
        }
        log.info("[SCRAPE] Parsing scraper output (${stdout.length} chars)")
        val profile = try {
            parseResult(stdout)
        } catch (e: Exception) {
            log.error("[SCRAPE] Could not parse the scraper output: ${e.message}. Start: ${stdout.take(500)}", e)
            throw e
        }
        val totalMs = System.currentTimeMillis() - startTime
        log.info("[SCRAPE] Done in ${totalMs / 1000}s — " +
            "watched=${profile.watched.size}, " +
            "watchlist=${profile.watchlist.size}, " +
            "favorites=${profile.favorites.size}, " +
            "lists=${profile.lists.size}, " +
            "errors=${profile.errors.size}")
        profile.errors.take(10).forEach { log.warn("[SCRAPE] Scraper error: $it") }
        return profile
    }

    private suspend fun parseResult(jsonStr: String): FilmowProfile {
        val obj = json.parseToJsonElement(jsonStr).jsonObject

        if (obj.containsKey("error")) {
            val errorMsg = obj["error"]?.jsonPrimitive?.content ?: "Unknown error"
            log.error("[SCRAPE] Scraper reported an error: $errorMsg")
            throw RuntimeException("Filmow scraper error: $errorMsg")
        }

        val (recentlyWatched, details1) = parseMovieListLight(obj["recentlyWatched"])
        val (watched, details2) = parseMovieListLight(obj["watched"])
        val (watchlist, details3) = parseMovieListLight(obj["watchlist"])
        val (favorites, details4) = parseMovieListLight(obj["favorites"])
        val (lists, details5) = parseListList(obj["lists"])

        return FilmowProfile(
            username = obj["username"]?.jsonPrimitive?.content ?: "",
            displayName = obj["displayName"]?.jsonPrimitive?.content ?: "",
            watchedCount = obj["watchedCount"]?.jsonPrimitive?.intOrNull ?: 0,
            recentlyWatched = recentlyWatched,
            watched = watched,
            watchlist = watchlist,
            favorites = favorites,
            lists = lists,
            errors = obj["errors"]?.jsonArray
                ?.map { it.jsonPrimitive.content }
                ?: emptyList(),
            filmowDetails = details1 + details2 + details3 + details4 + details5
        )
    }

    /**
     * Parses movies from scraped JSON without external API calls.
     * Returns (movies, filmowId → FilmowMoviePartial) where the partial carries
     * detail-page data (imdbUrl, runtime, director, genres) that is stored in the
     * DB but never serialized to API responses.
     */
    private fun parseMovieListLight(
        element: kotlinx.serialization.json.JsonElement?
    ): Pair<List<Movie>, Map<String, FilmowMoviePartial>> {
        if (element == null || element !is JsonArray) return Pair(emptyList(), emptyMap())

        val movies = mutableListOf<Movie>()
        val partials = mutableMapOf<String, FilmowMoviePartial>()

        for (item in element) {
            try {
                val obj = item.jsonObject
                val rawTitle = obj["title"]?.jsonPrimitive?.content ?: continue
                val year = obj["year"]?.jsonPrimitive?.content
                val cleanTitle = rawTitle.replace(Regex("\\(\\d{4}\\)"), "").trim()

                val filmowId = obj["filmowId"]?.jsonPrimitive?.content
                val posterUrl = obj["posterUrl"]?.jsonPrimitive?.content

                // Fields enriched from the detail page (only present for watched/recentlyWatched)
                val imdbUrl = obj["imdbUrl"]?.takeIf { it != JsonNull }?.jsonPrimitive?.content
                val runtime = obj["runtime"]?.takeIf { it != JsonNull }?.jsonPrimitive?.intOrNull
                val director = obj["director"]?.takeIf { it != JsonNull }?.jsonPrimitive?.content
                val genres = (obj["genres"]?.takeIf { it is JsonArray } as? JsonArray)
                    ?.mapNotNull { it.jsonPrimitive.content.takeIf(String::isNotBlank) }
                    ?: emptyList()

                if (filmowId != null && (imdbUrl != null || runtime != null || director != null || genres.isNotEmpty())) {
                    partials[filmowId] = FilmowMoviePartial(
                        imdbUrl = imdbUrl,
                        runtime = runtime,
                        director = director,
                        genres = genres
                    )
                }

                movies += Movie(
                    title = cleanTitle,
                    localTitle = obj["localTitle"]?.jsonPrimitive?.content,
                    originalTitle = obj["originalTitle"]?.jsonPrimitive?.content,
                    overview = obj["overview"]?.takeIf { it != JsonNull }?.jsonPrimitive?.content ?: "",
                    posterPath = posterUrl,
                    voteAverage = obj["voteAverage"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                    userRating = obj["userRating"]?.jsonPrimitive?.doubleOrNull,
                    releaseDate = obj["releaseDate"]?.takeIf { it != JsonNull }?.jsonPrimitive?.content,
                    filmowId = filmowId,
                    watchedAt = obj["watchedAt"]?.takeIf { it != JsonNull }?.jsonPrimitive?.content
                )
            } catch (e: Exception) {
                log.warn("Failed to parse movie item: ${e.message}")
            }
        }

        return Pair(movies, partials)
    }

    private fun parseListList(
        element: kotlinx.serialization.json.JsonElement?
    ): Pair<List<FilmowList>, Map<String, FilmowMoviePartial>> {
        if (element == null || element !is JsonArray) return Pair(emptyList(), emptyMap())

        val allPartials = mutableMapOf<String, FilmowMoviePartial>()
        val lists = element.mapNotNull { item ->
            try {
                val list = item.jsonObject
                val (movies, partials) = parseMovieListLight(list["movies"])
                allPartials += partials
                FilmowList(
                    filmowId = list["filmowId"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                    title = list["title"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                    description = list["description"]?.jsonPrimitive?.content,
                    filmowUrl = list["filmowUrl"]?.jsonPrimitive?.content ?: "",
                    coverUrl = list["coverUrl"]?.jsonPrimitive?.content,
                    movies = movies
                )
            } catch (e: Exception) {
                log.warn("Failed to parse list item: ${e.message}")
                null
            }
        }
        return Pair(lists, allPartials)
    }

    /** Only the cookie names: the values are the user's Filmow session and must never be logged. */
    private fun cookieNames(cookies: String): List<String> =
        cookies.split(";").mapNotNull { pair -> pair.substringBefore("=").trim().takeIf { it.isNotEmpty() } }

    /** Logs the scraper's stderr: [FATAL] and tracebacks as errors, refused requests as warnings. */
    private fun logScraperLine(line: String) {
        when {
            line.contains("[FATAL]") || line.contains("Traceback") || line.contains("Error:") -> pyLog.error(line)
            line.contains("[BLOCKED]") || line.contains("Rate limited") || line.contains("error:") -> pyLog.warn(line)
            else -> pyLog.info(line)
        }
    }

    private fun resolveVenvPython(): String {
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val venvPath = if (isWindows) VENV_PYTHON_WIN else VENV_PYTHON_UNIX

        val venvFile = File(venvPath)
        if (venvFile.exists()) {
            log.info("[SCRAPE] Using the venv Python: ${venvFile.absolutePath}")
            return venvFile.absolutePath
        }
        log.warn(
            "[SCRAPE] No venv at ${venvFile.absolutePath} (working dir: ${System.getProperty("user.dir")}); " +
                "falling back to the Python on the PATH"
        )

        // Alpine Linux has python3, not python. On Windows, python3 may be the Microsoft Store alias, which fails.
        for (candidate in listOf("python3", "python")) {
            val (exit, output) = runQuick(candidate, "--version")
            log.info("[SCRAPE] Python candidate '$candidate': exit=$exit, output=${output.ifBlank { "<none>" }}")
            if (exit == 0) return candidate
        }
        log.error("[SCRAPE] No working Python found on the PATH; trying 'python3' anyway")
        return "python3"
    }

    /** Fails early, with the fix, when the interpreter lacks the scraper's packages. */
    private fun checkDependencies(pythonBin: String) {
        val (exit, output) = runQuick(pythonBin, "-c", "import cloudscraper, bs4, sys; print(sys.executable, sys.version.split()[0])")
        if (exit == 0) {
            log.info("[SCRAPE] Python ready: $output")
            return
        }
        log.error(
            "[SCRAPE] Python '$pythonBin' cannot import the scraper's packages (exit=$exit): $output\n" +
                "[SCRAPE] Fix: python -m venv scripts/.venv && scripts/.venv/${if (File.separatorChar == '\\') "Scripts" else "bin"}/pip install -r scripts/requirements.txt"
        )
        throw IllegalStateException("Python '$pythonBin' is missing the scraper dependencies: ${output.lines().lastOrNull { it.isNotBlank() }}")
    }

    /** Runs a short command and returns (exit code, combined output); -1 when it cannot start or hangs. */
    private fun runQuick(vararg command: String): Pair<Int, String> = try {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val output = StringBuilder()
        val reader = thread(isDaemon = true) { output.append(process.inputStream.bufferedReader().readText()) }
        if (!process.waitFor(QUICK_CHECK_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            -1 to "timed out after ${QUICK_CHECK_SECONDS}s"
        } else {
            reader.join(1000)
            process.exitValue() to output.toString().trim()
        }
    } catch (e: Exception) {
        -1 to (e.message ?: e.javaClass.simpleName)
    }

    private fun resolveScriptPath(): File {
        val relative = File(SCRIPT_PATH)
        if (relative.exists()) return relative

        val jarDir = File(
            FilmowDataSourceImpl::class.java.protectionDomain.codeSource.location.toURI()
        ).parentFile
        val fromJar = File(jarDir, SCRIPT_PATH)
        if (fromJar.exists()) return fromJar

        log.warn("[SCRAPE] Scraper not found at ${relative.absolutePath} nor ${fromJar.absolutePath}")
        return relative
    }
}
