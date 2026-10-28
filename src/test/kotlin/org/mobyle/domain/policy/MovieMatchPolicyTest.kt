package org.mobyle.domain.policy

import org.mobyle.domain.model.ImportedMovie
import org.mobyle.domain.model.MovieCandidate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MovieMatchPolicyTest {

    private val cidade = ImportedMovie("f-1", "Cidade de Deus", year = 2002)

    private fun candidate(tmdbId: Int, title: String, year: Int?, movie: ImportedMovie = cidade) =
        MovieCandidate(tmdbId, title, year = year, score = MovieMatchPolicy.score(movie, listOf(title), year))

    @Test
    fun `accents, case and punctuation do not change the title`() {
        assertEquals(MovieMatchPolicy.normalize("Amélie: O Fabuloso Destino!"), MovieMatchPolicy.normalize("amelie o fabuloso destino"))
    }

    @Test
    fun `exact title and year match automatically`() {
        val match = MovieMatchPolicy.automaticMatch(cidade, listOf(candidate(1, "Cidade de Deus", 2002), candidate(2, "Cidade de Deus: 10 Anos Depois", 2013)))

        assertEquals(1, match?.tmdbId)
    }

    @Test
    fun `the original title also matches`() {
        val movie = ImportedMovie("f-2", "Psicose", originalTitle = "Psycho", year = 1960)
        val score = MovieMatchPolicy.score(movie, listOf("Psycho"), 1960)

        assertEquals(1.0, score, 1e-9)
    }

    @Test
    fun `one year off, a partial title or two exact remakes go to the queue`() {
        assertNull(MovieMatchPolicy.automaticMatch(cidade, listOf(candidate(1, "Cidade de Deus", 2003))))
        assertNull(MovieMatchPolicy.automaticMatch(cidade, listOf(candidate(1, "Cidade de Deus 2", 2002))))

        val noYear = ImportedMovie("f-3", "Nosferatu")
        val remakes = listOf(candidate(1, "Nosferatu", 1922, noYear), candidate(2, "Nosferatu", 2024, noYear))
        assertNull(MovieMatchPolicy.automaticMatch(noYear, remakes))
        assertEquals(1, MovieMatchPolicy.automaticMatch(noYear, remakes.take(1))?.tmdbId)
    }

    @Test
    fun `a partial title never scores as an exact one`() {
        val partial = MovieMatchPolicy.score(cidade, listOf("Cidade de Deus 2"), 2002)
        val exact = MovieMatchPolicy.score(cidade, listOf("Cidade de Deus"), 2002)

        assertTrue(partial < exact)
        assertTrue(partial < 0.9)
    }
}
