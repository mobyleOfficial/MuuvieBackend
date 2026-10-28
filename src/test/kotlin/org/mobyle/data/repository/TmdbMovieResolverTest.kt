package org.mobyle.data.repository

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.mobyle.data.remote.tmdb.TmdbDataSource
import org.mobyle.data.remote.tmdb.model.TmdbAlternativeTitle
import org.mobyle.data.remote.tmdb.model.TmdbAlternativeTitles
import org.mobyle.data.remote.tmdb.model.TmdbCredits
import org.mobyle.data.remote.tmdb.model.TmdbCrewMember
import org.mobyle.data.remote.tmdb.model.TmdbFindResponse
import org.mobyle.data.remote.tmdb.model.TmdbMovieFactsResponse
import org.mobyle.data.remote.tmdb.model.TmdbMovieResponse
import org.mobyle.domain.model.ImportedMovie
import org.mobyle.domain.model.MovieCandidate
import org.mobyle.domain.model.MovieResolution
import org.mobyle.domain.model.vocabulary.MovieMatchMethod
import org.mobyle.domain.repository.MovieCandidateSearch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TmdbMovieResolverTest {

    private val tmdb = mockk<TmdbDataSource>()
    private val search = mockk<MovieCandidateSearch>()
    private val resolver = TmdbMovieResolver(tmdb, search)

    private fun facts(id: Int, title: String, year: Int, director: String, alternative: List<String> = emptyList()) =
        TmdbMovieFactsResponse(
            id = id,
            title = title,
            originalTitle = title,
            releaseDate = "$year-01-01",
            credits = TmdbCredits(crew = listOf(TmdbCrewMember(name = director, job = "Director"))),
            alternativeTitles = TmdbAlternativeTitles(alternative.map { TmdbAlternativeTitle(it) })
        )

    @Test
    fun `the IMDb link resolves without searching`() = runBlocking {
        coEvery { tmdb.findByImdbId("tt0054215") } returns TmdbFindResponse(listOf(TmdbMovieResponse(id = 539)))

        val result = resolver.resolve(ImportedMovie("f-1", "Psicose", imdbUrl = "https://www.imdb.com/title/tt0054215/"))

        assertEquals(MovieResolution.Matched(539, MovieMatchMethod.IMDB), result)
        coVerify(exactly = 0) { search.candidates(any()) }
    }

    @Test
    fun `an exact title and year match by title`() = runBlocking {
        val movie = ImportedMovie("f-2", "Cidade de Deus", year = 2002)
        coEvery { search.candidates(movie) } returns listOf(MovieCandidate(598, "Cidade de Deus", year = 2002, score = 1.0))

        assertEquals(MovieResolution.Matched(598, MovieMatchMethod.TITLE), resolver.resolve(movie))
    }

    @Test
    fun `the director confirms a remake the title alone cannot tell apart`() = runBlocking {
        val movie = ImportedMovie("f-3", "Nosferatu", originalTitle = "Nosferatu", director = "Robert Eggers")
        coEvery { search.candidates(movie) } returns listOf(
            MovieCandidate(653, "Nosferatu", year = 1922, score = 0.85),
            MovieCandidate(426063, "Nosferatu", year = 2024, score = 0.85)
        )
        coEvery { tmdb.getMovieFacts(653) } returns facts(653, "Nosferatu", 1922, "F. W. Murnau")
        coEvery { tmdb.getMovieFacts(426063) } returns facts(426063, "Nosferatu", 2024, "Robert Eggers")

        assertEquals(MovieResolution.Matched(426063, MovieMatchMethod.DIRECTOR), resolver.resolve(movie))
    }

    @Test
    fun `an alternative title and the director match a translated title`() = runBlocking {
        val movie = ImportedMovie("f-4", "O Auto da Compadecida", year = 2000, director = "Guel Arraes")
        coEvery { search.candidates(movie) } returns listOf(MovieCandidate(40096, "A Dog's Will", year = 2000, score = 0.3))
        coEvery { tmdb.getMovieFacts(40096) } returns
            facts(40096, "A Dog's Will", 2000, "Guel Arraes", alternative = listOf("O Auto da Compadecida"))

        assertEquals(MovieResolution.Matched(40096, MovieMatchMethod.DIRECTOR), resolver.resolve(movie))
    }

    @Test
    fun `without a director an ambiguous movie stays unmatched with its candidates`() = runBlocking {
        val movie = ImportedMovie("f-5", "Nosferatu")
        val candidates = listOf(
            MovieCandidate(653, "Nosferatu", year = 1922, score = 0.85),
            MovieCandidate(426063, "Nosferatu", year = 2024, score = 0.85)
        )
        coEvery { search.candidates(movie) } returns candidates

        val result = resolver.resolve(movie)

        assertIs<MovieResolution.Unmatched>(result)
        assertEquals(candidates, result.candidates)
        coVerify(exactly = 0) { tmdb.getMovieFacts(any()) }
    }
}
