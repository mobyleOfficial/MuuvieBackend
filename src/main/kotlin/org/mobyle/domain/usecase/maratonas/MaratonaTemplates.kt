package org.mobyle.domain.usecase.maratonas

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.mobyle.domain.model.ChallengeCriteria
import org.mobyle.domain.model.CriteriaField
import org.mobyle.domain.model.CriteriaRule
import org.mobyle.domain.model.Maratona
import org.mobyle.domain.model.NotFoundException
import org.mobyle.domain.model.ValidationException

/** A ready-made maratona (F7.9): the challenges and a suggested length in days. */
@Serializable
data class MaratonaTemplate(
    val id: String,
    val title: String,
    val description: String,
    val days: Int,
    val accentColor: String,
    val categories: List<CategoryInput>
) {
    val totalChallenges: Int get() = categories.sumOf { it.challenges.size }
}

/** Body of POST /maratonas/from-template/{id}. */
@Serializable
data class FromTemplateInput(val slug: String = "", val startsOn: String = "", val title: String? = null)

class GetMaratonaTemplates {
    operator fun invoke(): List<MaratonaTemplate> = MaratonaTemplates.all
}

/** Creates a maratona from a template; the owner edits it afterwards like any other. */
class CreateMaratonaFromTemplate(private val create: CreateMaratona) {
    operator fun invoke(userId: String, templateId: String, input: FromTemplateInput): Maratona {
        val template = MaratonaTemplates.all.firstOrNull { it.id == templateId } ?: throw NotFoundException("Template not found")
        val startsOn = runCatching { LocalDate.parse(input.startsOn.trim()) }
            .getOrElse { throw ValidationException("startsOn", "startsOn must be YYYY-MM-DD") }
        return create(
            userId,
            MaratonaInput(
                slug = input.slug,
                title = input.title?.takeIf { it.isNotBlank() } ?: template.title,
                description = template.description,
                accentColor = template.accentColor,
                startsOn = startsOn.toString(),
                endsOn = startsOn.plus(template.days - 1, DateTimeUnit.DAY).toString(),
                categories = template.categories
            ),
            templateId = template.id
        )
    }
}

/** TMDB genre ids used by the templates. */
private object Genre {
    const val HORROR = 27
    const val THRILLER = 53
    const val ANIMATION = 16
    const val SCIENCE_FICTION = 878
}

object MaratonaTemplates {
    private val HORROR_DECADES = listOf(1960 to "60", 1970 to "70", 1980 to "80", 1990 to "90", 2000 to "2000", 2010 to "2010", 2020 to "2020")

    val all: List<MaratonaTemplate> by lazy {
        listOf(aroundTheWorld(), oscar(), halloween(), decades(), directors())
    }

    private fun aroundTheWorld() = MaratonaTemplate(
        id = "volta-ao-mundo",
        title = "Volta ao mundo em 30 filmes",
        description = "Um filme de cada país, passando por todos os continentes.",
        days = 90,
        accentColor = "#2E7D32",
        categories = listOf(
            "América do Sul" to listOf("BR" to "Brasil", "AR" to "Argentina", "CL" to "Chile", "CO" to "Colômbia", "PE" to "Peru"),
            "América do Norte e Central" to listOf("US" to "Estados Unidos", "CA" to "Canadá", "MX" to "México", "CU" to "Cuba"),
            "Europa" to listOf(
                "FR" to "França", "IT" to "Itália", "DE" to "Alemanha", "ES" to "Espanha", "GB" to "Reino Unido",
                "PT" to "Portugal", "SE" to "Suécia", "RU" to "Rússia"
            ),
            "África" to listOf("NG" to "Nigéria", "SN" to "Senegal", "ZA" to "África do Sul", "EG" to "Egito", "MA" to "Marrocos"),
            "Ásia" to listOf("JP" to "Japão", "KR" to "Coreia do Sul", "CN" to "China", "IN" to "Índia", "IR" to "Irã", "TH" to "Tailândia"),
            "Oceania" to listOf("AU" to "Austrália", "NZ" to "Nova Zelândia")
        ).map { (continent, countries) ->
            CategoryInput(
                name = continent,
                challenges = countries.map { (code, name) ->
                    ChallengeInput(description = "Um filme de $name", criteria = criteria(rule(CriteriaField.COUNTRY, listOf(code))))
                }
            )
        }
    )

    // The nominations are not in the catalog: these challenges are marked by hand.
    private fun oscar() = MaratonaTemplate(
        id = "oscar-2027",
        title = "Oscar 2027",
        description = "Assista aos indicados antes da cerimônia.",
        days = 60,
        accentColor = "#C9A227",
        categories = listOf(
            CategoryInput(
                name = "Indicados",
                challenges = listOf(
                    "Um indicado a Melhor Filme",
                    "Outro indicado a Melhor Filme",
                    "Um indicado a Melhor Direção",
                    "Um indicado a Melhor Filme Internacional",
                    "Um indicado a Melhor Animação",
                    "Um indicado a Melhor Documentário",
                    "Um filme com atuação indicada",
                    "Um indicado a Melhor Roteiro"
                ).map { ChallengeInput(description = it) }
            ),
            CategoryInput(
                name = "Revisitando",
                challenges = listOf(ChallengeInput(description = "Um vencedor de Melhor Filme de qualquer ano"))
            )
        )
    )

    private fun halloween() = MaratonaTemplate(
        id = "halloween",
        title = "Halloween",
        description = "Um mês de terror, de todas as épocas e lugares.",
        days = 31,
        accentColor = "#E65100",
        categories = listOf(
            CategoryInput(
                name = "Pelas décadas",
                challenges = HORROR_DECADES.map { (decade, label) ->
                    ChallengeInput(
                        description = "Um terror dos anos $label",
                        criteria = criteria(rule(CriteriaField.GENRE, listOf(Genre.HORROR)), rule(CriteriaField.DECADE, listOf(decade)))
                    )
                }
            ),
            CategoryInput(
                name = "Pelo mundo",
                challenges = listOf(
                    ChallengeInput(
                        description = "Um terror brasileiro",
                        criteria = criteria(rule(CriteriaField.GENRE, listOf(Genre.HORROR)), rule(CriteriaField.COUNTRY, listOf("BR")))
                    ),
                    ChallengeInput(
                        description = "Um terror japonês ou coreano",
                        criteria = criteria(rule(CriteriaField.GENRE, listOf(Genre.HORROR)), rule(CriteriaField.COUNTRY, listOf("JP", "KR")))
                    ),
                    ChallengeInput(
                        description = "Um terror europeu",
                        criteria = criteria(
                            rule(CriteriaField.GENRE, listOf(Genre.HORROR)),
                            rule(CriteriaField.COUNTRY, listOf("FR", "IT", "ES", "DE", "GB", "SE", "NO", "DK"))
                        )
                    )
                )
            ),
            CategoryInput(
                name = "Variações",
                challenges = listOf(
                    ChallengeInput(
                        description = "Um terror com mais de 2 horas",
                        criteria = criteria(rule(CriteriaField.GENRE, listOf(Genre.HORROR)), rule(CriteriaField.RUNTIME_MIN, 120))
                    ),
                    ChallengeInput(
                        description = "Uma animação de terror",
                        criteria = criteria(rule(CriteriaField.GENRE, listOf(Genre.HORROR)), rule(CriteriaField.GENRE, listOf(Genre.ANIMATION)))
                    ),
                    ChallengeInput(
                        description = "Um terror de ficção científica",
                        criteria = criteria(rule(CriteriaField.GENRE, listOf(Genre.HORROR)), rule(CriteriaField.GENRE, listOf(Genre.SCIENCE_FICTION)))
                    ),
                    ChallengeInput(description = "Um suspense", criteria = criteria(rule(CriteriaField.GENRE, listOf(Genre.THRILLER)))),
                    ChallengeInput(description = "Um terror em preto e branco")
                )
            )
        )
    )

    private fun decades() = MaratonaTemplate(
        id = "decada-por-decada",
        title = "Década por década",
        description = "Um filme de cada década, do cinema mudo até hoje.",
        days = 120,
        accentColor = "#5E35B1",
        categories = listOf(
            CategoryInput(
                name = "Décadas",
                challenges = (1920..2020 step 10).map { decade ->
                    ChallengeInput(description = "Um filme da década de $decade", criteria = criteria(rule(CriteriaField.DECADE, listOf(decade))))
                }
            )
        )
    )

    // The catalog has no gender for directors: these challenges are marked by hand.
    private fun directors() = MaratonaTemplate(
        id = "diretoras",
        title = "Diretoras",
        description = "Filmes dirigidos por mulheres, de várias épocas e lugares.",
        days = 60,
        accentColor = "#AD1457",
        categories = listOf(
            CategoryInput(
                name = "Diretoras",
                challenges = listOf(
                    ChallengeInput(description = "Um filme dirigido por uma brasileira"),
                    ChallengeInput(description = "Um filme dirigido por uma mulher antes de 1980"),
                    ChallengeInput(description = "O primeiro longa de uma diretora"),
                    ChallengeInput(description = "Um filme de uma diretora asiática"),
                    ChallengeInput(description = "Um filme de uma diretora africana"),
                    ChallengeInput(description = "Uma animação dirigida por uma mulher"),
                    ChallengeInput(description = "Um documentário dirigido por uma mulher")
                )
            )
        )
    )

    private fun criteria(vararg rules: CriteriaRule) = ChallengeCriteria(rules.toList())

    private fun rule(field: CriteriaField, values: List<Any>): CriteriaRule =
        CriteriaRule(field.wireValue, field.op, JsonArray(values.map(::primitive)))

    private fun rule(field: CriteriaField, value: Int): CriteriaRule = CriteriaRule(field.wireValue, field.op, JsonPrimitive(value))

    private fun primitive(value: Any): JsonElement = when (value) {
        is Int -> JsonPrimitive(value)
        else -> JsonPrimitive(value.toString())
    }
}
