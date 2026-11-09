package org.mobyle.domain.usecase.movies

import org.mobyle.domain.model.MovieTraits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SimilarityScorerTest {

    private val source = MovieTraits(
        genres = setOf("Ficção científica", "Terror"),
        keywords = setOf("alien", "spaceship", "android")
    )

    @Test
    fun `without traits the score follows TMDB order around a neutral middle`() {
        val affinities = SimilarityScorer.score(MovieTraits(), listOf(10L, 11L, 12L), emptyMap())

        assertEquals(listOf(0.7, 0.5, 0.3), affinities.map { affinity -> affinity.score })
        assertTrue(affinities.all { affinity -> affinity.sharedGenres.isEmpty() && affinity.sharedKeywords.isEmpty() })
    }

    @Test
    fun `a single similar movie without traits scores 0,7`() {
        assertEquals(0.7, SimilarityScorer.score(source, listOf(10L), emptyMap()).single().score)
    }

    @Test
    fun `identical traits outrank TMDB order`() {
        val affinities = SimilarityScorer.score(
            source = source,
            similarMovieIds = listOf(10L, 11L),
            traitsById = mapOf(
                10L to MovieTraits(genres = setOf("Comédia"), keywords = setOf("wedding")),
                11L to source
            )
        )

        val (first, last) = affinities
        assertEquals(0.14, first.score) // only rank: 0.2 × 0.7 over the full weight
        assertEquals(0.86, last.score) // 0.2 × 0.3 + 0.35 + 0.45
        assertTrue(last.score > first.score)
        assertEquals(listOf("Ficção científica", "Terror"), last.sharedGenres)
        assertEquals(listOf("alien", "android", "spaceship"), last.sharedKeywords)
    }

    @Test
    fun `a signal one side lacks is left out and its weight shared`() {
        val affinity = SimilarityScorer.score(
            source = source,
            similarMovieIds = listOf(10L),
            traitsById = mapOf(10L to MovieTraits(genres = setOf("Terror", "Drama")))
        ).single()

        // rank 0.7 at weight 0.2, genres 1/2 at weight 0.35, keywords unknown
        assertEquals(0.573, affinity.score)
        assertEquals(listOf("Terror"), affinity.sharedGenres)
    }

    @Test
    fun `scores stay between 0 and 1`() {
        val affinities = SimilarityScorer.score(
            source = source,
            similarMovieIds = (1L..10L).toList(),
            traitsById = (1L..10L).associateWith { MovieTraits(genres = setOf("Drama"), keywords = setOf("love")) }
        )

        assertTrue(affinities.all { affinity -> affinity.score in 0.0..1.0 })
    }
}
