package org.mobyle.domain.policy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ArticleMentionPolicyTest {

    @Test
    fun `quoted titles are mentions, with the year when it follows in parentheses`() {
        val mentions = ArticleMentionPolicy.mentions(
            "Fernanda Torres brilha em “Ainda Estou Aqui” (2024)",
            "O diretor de \"Central do Brasil\" volta ao Oscar."
        )

        assertEquals(
            listOf(MovieMention("Ainda Estou Aqui", 2024), MovieMention("Central do Brasil", null)),
            mentions
        )
    }

    @Test
    fun `speech quotes, lowercase spans and long sentences are not titles`() {
        val mentions = ArticleMentionPolicy.mentions(
            "\"Foi uma honra trabalhar com ele.\", disse a atriz. Um filme \"sensível\" e " +
                "\"Uma história sobre memória, família e resistência durante a ditadura no Brasil\"."
        )

        assertEquals(emptyList(), mentions)
    }

    @Test
    fun `the same title is one mention and the one with a year wins`() {
        val mentions = ArticleMentionPolicy.mentions("\"Duna\" estreia hoje", "Veja o trailer de \"Duna\" (2021)")

        assertEquals(listOf(MovieMention("Duna", 2021)), mentions)
    }

    @Test
    fun `a mention with a year links to the exact title of that year`() {
        val match = ArticleMentionPolicy.match(
            MovieMention("Duna", 2021),
            listOf(candidate(1, "Duna", 1984, votes = 2000), candidate(2, "Duna", 2021, votes = 9000))
        )

        assertEquals(2 to 1.0f, match?.let { it.first.tmdbId to it.second })
    }

    @Test
    fun `without a year an exact title needs votes, and a partial title never links`() {
        assertNull(ArticleMentionPolicy.match(MovieMention("Sim", null), listOf(candidate(1, "Sim", 2010, votes = 3))))
        assertNull(ArticleMentionPolicy.match(MovieMention("Duna", null), listOf(candidate(1, "Duna: Parte Dois", 2024, votes = 9000))))

        val match = ArticleMentionPolicy.match(
            MovieMention("Ainda estou aqui", null),
            listOf(candidate(7, "Ainda Estou Aqui", 2024, votes = 800))
        )
        assertEquals(7 to ArticleMentionPolicy.MIN_CONFIDENCE, match?.let { it.first.tmdbId to it.second })
    }

    @Test
    fun `the original title matches too`() {
        val match = ArticleMentionPolicy.match(
            MovieMention("The Substance", 2024),
            listOf(MentionCandidate(3, "A Substância", "The Substance", 2024, voteCount = 4000))
        )

        assertEquals(3, match?.first?.tmdbId)
    }

    private fun candidate(id: Int, title: String, year: Int, votes: Int) =
        MentionCandidate(id, title, originalTitle = null, year = year, voteCount = votes)
}
