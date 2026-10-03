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
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    companion object {
        private const val SCRIPT_PATH = "scripts/filmow_scraper.py"
        private const val VENV_PYTHON_WIN = "scripts/.venv/Scripts/python.exe"
        private const val VENV_PYTHON_UNIX = "scripts/.venv/bin/python"
        private const val TIMEOUT_MINUTES = 20L
    }

    override suspend fun scrapeProfile(cookies: String, username: String): FilmowProfile {
        val scriptFile = resolveScriptPath()
        if (!scriptFile.exists()) {
            throw IllegalStateException("Python scraper not found at: ${scriptFile.absolutePath}")
        }

        val pythonBin = resolveVenvPython()

        val command = mutableListOf(pythonBin, scriptFile.absolutePath, username)
        if (cookies.isNotBlank()) {
            command.add(cookies)
        }

        val startTime = System.currentTimeMillis()

        val process = ProcessBuilder(command)
            .redirectErrorStream(false)
            .start()

        val stderrThread = thread(isDaemon = true, name = "scraper-stderr") {
            process.errorStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    println("[SCRAPE/py] $line")
                }
            }
        }

        val stdout = process.inputStream.bufferedReader().readText()

        val exited = process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES)
        stderrThread.join(5000)
        if (!exited) {
            process.destroyForcibly()
            throw RuntimeException("Filmow scraper timed out after $TIMEOUT_MINUTES minutes")
        }

        if (process.exitValue() != 0) {
            throw RuntimeException("Filmow scraper failed: $stdout")
        }

        val profile = parseResult(stdout)
        val totalMs = System.currentTimeMillis() - startTime
        log.info("[SCRAPE] Done in ${totalMs / 1000}s — " +
            "watched=${profile.watched.size}, " +
            "watchlist=${profile.watchlist.size}, " +
            "favorites=${profile.favorites.size}, " +
            "lists=${profile.lists.size}")
        return profile
    }

    private suspend fun parseResult(jsonStr: String): FilmowProfile {
        val obj = json.parseToJsonElement(jsonStr).jsonObject

        if (obj.containsKey("error")) {
            val errorMsg = obj["error"]?.jsonPrimitive?.content ?: "Unknown error"
            throw RuntimeException("Filmow scraper error: $errorMsg")
        }

        val (recentlyWatched, details1) = parseMovieListLight(obj["recentlyWatched"])
        val (watched, details2) = parseMovieListLight(obj["watched"])
        val (watchlist, _) = parseMovieListLight(obj["watchlist"])
        val (favorites, _) = parseMovieListLight(obj["favorites"])

        return FilmowProfile(
            username = obj["username"]?.jsonPrimitive?.content ?: "",
            displayName = obj["displayName"]?.jsonPrimitive?.content ?: "",
            watchedCount = obj["watchedCount"]?.jsonPrimitive?.intOrNull ?: 0,
            recentlyWatched = recentlyWatched,
            watched = watched,
            watchlist = watchlist,
            favorites = favorites,
            lists = parseListList(obj["lists"]),
            errors = obj["errors"]?.jsonArray
                ?.map { it.jsonPrimitive.content }
                ?: emptyList(),
            filmowDetails = details1 + details2
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

    private fun parseListList(element: kotlinx.serialization.json.JsonElement?): List<FilmowList> {
        if (element == null || element !is JsonArray) return emptyList()

        return element.mapNotNull { item ->
            try {
                val list = item.jsonObject
                FilmowList(
                    filmowId = list["filmowId"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                    title = list["title"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                    description = list["description"]?.jsonPrimitive?.content,
                    filmowUrl = list["filmowUrl"]?.jsonPrimitive?.content ?: "",
                    coverUrl = list["coverUrl"]?.jsonPrimitive?.content,
                    movies = parseMovieListLight(list["movies"]).first
                )
            } catch (e: Exception) {
                log.warn("Failed to parse list item: ${e.message}")
                null
            }
        }
    }

    private fun resolveVenvPython(): String {
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val venvPath = if (isWindows) VENV_PYTHON_WIN else VENV_PYTHON_UNIX

        val venvFile = File(venvPath)
        if (venvFile.exists()) return venvFile.absolutePath

        // Alpine Linux has python3, not python
        for (candidate in listOf("python3", "python")) {
            try {
                val check = ProcessBuilder(candidate, "--version").start()
                if (check.waitFor() == 0) return candidate
            } catch (_: Exception) { }
        }
        return "python3"
    }

    private fun resolveScriptPath(): File {
        val relative = File(SCRIPT_PATH)
        if (relative.exists()) return relative

        val jarDir = File(
            FilmowDataSourceImpl::class.java.protectionDomain.codeSource.location.toURI()
        ).parentFile
        val fromJar = File(jarDir, SCRIPT_PATH)
        if (fromJar.exists()) return fromJar

        return relative
    }
}
