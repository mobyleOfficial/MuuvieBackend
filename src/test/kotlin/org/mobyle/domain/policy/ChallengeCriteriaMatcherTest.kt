package org.mobyle.domain.policy

import kotlinx.serialization.json.Json
import org.mobyle.data.repository.MaratonaCatalogImpl
import org.mobyle.domain.model.ChallengeCriteria
import org.mobyle.domain.model.MovieFacts
import org.mobyle.domain.model.ValidationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChallengeCriteriaMatcherTest {

    private fun criteria(json: String) = Json.decodeFromString(ChallengeCriteria.serializer(), json)

    private val horror70s = criteria("""{"all":[{"field":"genre","op":"in","value":[27]},{"field":"decade","op":"in","value":[1970]}]}""")

    private val suspiria = MovieFacts(
        movieId = 1,
        genreIds = setOf(27),
        year = 1977,
        countries = setOf("IT"),
        originalLanguage = "it",
        directorIds = setOf(4955),
        castIds = setOf(10),
        runtime = 99,
        keywordIds = setOf(900),
        voteAverage = 7.3
    )

    @Test
    fun `suspiria completes a horror from the 70s`() {
        assertTrue(ChallengeCriteriaMatcher.matches(horror70s, suspiria))
    }

    @Test
    fun `every rule must match`() {
        assertFalse(ChallengeCriteriaMatcher.matches(horror70s, suspiria.copy(year = 1981)))
        assertFalse(ChallengeCriteriaMatcher.matches(horror70s, suspiria.copy(genreIds = setOf(18))))
    }

    @Test
    fun `each field reads its metadata`() {
        fun matches(json: String) = ChallengeCriteriaMatcher.matches(criteria("""{"all":[$json]}"""), suspiria)

        assertTrue(matches("""{"field":"release_year","op":"between","value":[1970,1979]}"""))
        assertTrue(matches("""{"field":"country","op":"in","value":["FR","IT"]}"""))
        assertTrue(matches("""{"field":"original_language","op":"in","value":["it"]}"""))
        assertTrue(matches("""{"field":"director","op":"in","value":[4955]}"""))
        assertTrue(matches("""{"field":"cast","op":"in","value":[10]}"""))
        assertTrue(matches("""{"field":"runtime_max","op":"lte","value":100}"""))
        assertFalse(matches("""{"field":"runtime_min","op":"gte","value":180}"""))
        assertTrue(matches("""{"field":"keyword","op":"in","value":[900]}"""))
        assertTrue(matches("""{"field":"vote_average_min","op":"gte","value":7}"""))
    }

    @Test
    fun `metadata the catalog does not have never matches`() {
        val unknown = suspiria.copy(year = null, runtime = null, countries = emptySet(), originalLanguage = null)

        assertFalse(ChallengeCriteriaMatcher.matches(horror70s, unknown))
        assertFalse(ChallengeCriteriaMatcher.matches(criteria("""{"all":[{"field":"runtime_max","op":"lte","value":90}]}"""), unknown))
        assertFalse(ChallengeCriteriaMatcher.matches(criteria("""{"all":[{"field":"country","op":"in","value":["IT"]}]}"""), unknown))
    }

    @Test
    fun `validation normalizes codes and years`() {
        val normalized = ChallengeCriteriaMatcher.validate(
            criteria(
                """{"all":[{"field":"country","op":"in","value":["br","BR"]},""" +
                    """{"field":"release_year","op":"between","value":[1999,1990]}]}"""
            )
        )

        assertEquals(listOf("BR"), normalized.all[0].strings())
        assertEquals(listOf(1990, 1999), normalized.all[1].ints())
    }

    @Test
    fun `invalid criteria are rejected`() {
        listOf(
            """{"all":[]}""",
            """{"all":[{"field":"mood","op":"in","value":[1]}]}""",
            """{"all":[{"field":"genre","op":"between","value":[27]}]}""",
            """{"all":[{"field":"genre","op":"in","value":[]}]}""",
            """{"all":[{"field":"decade","op":"in","value":[1975]}]}""",
            """{"all":[{"field":"release_year","op":"between","value":[1970]}]}""",
            """{"all":[{"field":"country","op":"in","value":["Brazil"]}]}""",
            """{"all":[{"field":"runtime_min","op":"gte","value":0}]}""",
            """{"all":[{"field":"vote_average_min","op":"gte","value":11}]}"""
        ).forEach { json ->
            assertFailsWith<ValidationException>(json) { ChallengeCriteriaMatcher.validate(criteria(json)) }
        }
    }

    @Test
    fun `criteria become TMDB discover parameters`() {
        val params = MaratonaCatalogImpl.discoverParams(
            criteria(
                """{"all":[{"field":"genre","op":"in","value":[27,53]},{"field":"genre","op":"in","value":[16]},""" +
                    """{"field":"decade","op":"in","value":[1970,1980]},{"field":"release_year","op":"between","value":[1975,2000]},""" +
                    """{"field":"country","op":"in","value":["IT","FR"]},{"field":"runtime_min","op":"gte","value":120}]}"""
            )
        )

        assertEquals("27|53,16", params["with_genres"])
        assertEquals("1975-01-01", params["primary_release_date.gte"])
        assertEquals("1989-12-31", params["primary_release_date.lte"])
        assertEquals("IT|FR", params["with_origin_country"])
        assertEquals("120", params["with_runtime.gte"])
    }
}
