package org.mobyle.domain.policy

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.mobyle.domain.model.ChallengeCriteria
import org.mobyle.domain.model.CriteriaRule
import org.mobyle.domain.model.MissionDefinition
import org.mobyle.domain.model.MissionType
import org.mobyle.domain.model.PetDefinition
import org.mobyle.domain.model.PetRarity
import org.mobyle.domain.model.PetRule
import org.mobyle.domain.model.PetRuleType
import org.mobyle.domain.model.WatchLog

/**
 * The bichinhos of the Bombonière (F10.1). The first three are the starter pack ("baldes de
 * pipoca"); the others unlock by what the user watches. Slugs are stable: the app draws and
 * names each one by its slug.
 */
object PetCatalog {
    val all: List<PetDefinition> = listOf(
        PetDefinition("pipoca-doce", PetRarity.STARTER, PetRule(PetRuleType.STARTER, 0)),
        PetDefinition("pipoca-salgada", PetRarity.STARTER, PetRule(PetRuleType.STARTER, 0)),
        PetDefinition("pipoca-caramelo", PetRarity.STARTER, PetRule(PetRuleType.STARTER, 0)),
        PetDefinition("claquete", PetRarity.COMMON, PetRule(PetRuleType.MOVIES, 10)),
        PetDefinition("ingresso", PetRarity.COMMON, PetRule(PetRuleType.MINUTES, 1_000)),
        PetDefinition("fantasminha", PetRarity.COMMON, PetRule(PetRuleType.GENRE_MOVIES, 5, genreId = 27)),
        PetDefinition("robozinho", PetRarity.COMMON, PetRule(PetRuleType.GENRE_MOVIES, 5, genreId = 878)),
        PetDefinition("lanterninha", PetRarity.COMMON, PetRule(PetRuleType.CINECLUBES_JOINED, 1)),
        PetDefinition("rolo-de-filme", PetRarity.RARE, PetRule(PetRuleType.MOVIES, 50)),
        PetDefinition("dinossauro", PetRarity.RARE, PetRule(PetRuleType.OLD_MOVIES, 10, beforeYear = 1970)),
        PetDefinition("coruja", PetRarity.RARE, PetRule(PetRuleType.MISSIONS, 10)),
        PetDefinition("trofeu", PetRarity.RARE, PetRule(PetRuleType.MARATONAS_FINISHED, 1)),
        PetDefinition("projetor", PetRarity.LEGENDARY, PetRule(PetRuleType.MINUTES, 30_000)),
        PetDefinition("estatueta", PetRarity.LEGENDARY, PetRule(PetRuleType.MOVIES, 250))
    )

    val starters: List<PetDefinition> = all.filter { it.rule.type == PetRuleType.STARTER }

    fun find(slug: String): PetDefinition? = all.firstOrNull { it.slug == slug }
}

/** XP and levels of the bichinho (F10.1). Imported sessions give nothing. */
object XpCalculator {
    const val MAX_LEVEL = 50
    private const val FINISHED_XP = 20
    private const val MAX_MINUTES_XP = 30
    private const val MINUTES_PER_XP = 10
    private const val LEVEL_STEP = 50

    fun forSession(log: WatchLog): Int =
        (if (log.completed) FINISHED_XP else 0) + ((log.minutesWatched ?: 0) / MINUTES_PER_XP).coerceAtMost(MAX_MINUTES_XP)

    /** Total XP to reach [level]: 0, 100, 300, 600… (50 × n × (n − 1)). */
    fun xpFor(level: Int): Int = LEVEL_STEP * level * (level - 1)

    fun level(xp: Int): Int {
        var level = 1
        while (level < MAX_LEVEL && xp >= xpFor(level + 1)) level++
        return level
    }
}

/**
 * The weekly missions (F10.2). Every week, from Monday, the same three come for everyone,
 * picked from the pool by the week number. Progress is counted from the week's sessions.
 */
object MissionCatalog {
    const val PER_WEEK = 3

    private fun criteria(field: String, op: String, vararg values: Int) = ChallengeCriteria(
        listOf(CriteriaRule(field, op, if (op == "in" || op == "between") JsonArray(values.map { JsonPrimitive(it) }) else JsonPrimitive(values.first())))
    )

    private fun countries(vararg codes: String) = ChallengeCriteria(
        listOf(CriteriaRule("country", "in", JsonArray(codes.map { JsonPrimitive(it) })))
    )

    val pool: List<MissionDefinition> = listOf(
        MissionDefinition("three-sessions", MissionType.SESSIONS, 3, 30),
        MissionDefinition("two-movies", MissionType.MOVIES, 2, 40),
        MissionDefinition("five-movies", MissionType.MOVIES, 5, 80),
        MissionDefinition("feelings", MissionType.EMOTIONS, 3, 30),
        MissionDefinition("long-movie", MissionType.CRITERIA_MOVIES, 1, 40, criteria("runtime_min", "gte", 150)),
        MissionDefinition("classic", MissionType.CRITERIA_MOVIES, 1, 40, criteria("release_year", "between", 1900, 1969)),
        MissionDefinition("documentary", MissionType.CRITERIA_MOVIES, 1, 40, criteria("genre", "in", 99)),
        MissionDefinition("horror", MissionType.CRITERIA_MOVIES, 1, 40, criteria("genre", "in", 27)),
        MissionDefinition("animation", MissionType.CRITERIA_MOVIES, 1, 40, criteria("genre", "in", 16)),
        MissionDefinition(
            "asia", MissionType.CRITERIA_MOVIES, 1, 50,
            countries("JP", "KR", "CN", "HK", "TW", "IN", "TH", "IR", "PH", "ID", "VN", "MY", "SG", "TR", "IL", "LB")
        ),
        MissionDefinition(
            "africa", MissionType.CRITERIA_MOVIES, 1, 60,
            countries("NG", "ZA", "EG", "SN", "MA", "TN", "KE", "ET", "GH", "ML", "BF", "CM", "DZ", "CI")
        ),
        MissionDefinition("brazil", MissionType.CRITERIA_MOVIES, 1, 40, countries("BR")),
        MissionDefinition("cineclube", MissionType.CINECLUBE, 1, 50),
        MissionDefinition("challenge", MissionType.CHALLENGE, 1, 50)
    )

    fun weekStart(day: LocalDate): LocalDate = day.minus(DatePeriod(days = day.dayOfWeek.ordinal - DayOfWeek.MONDAY.ordinal))

    fun weekEnd(day: LocalDate): LocalDate = weekStart(day).plus(DatePeriod(days = 6))

    /** The missions of the week that starts on [weekStart]: distinct, the same for everyone. */
    fun of(weekStart: LocalDate): List<MissionDefinition> {
        // 3 and 5 share no factor with the pool size, so the offset walks the whole pool and
        // the three picks of a week never repeat.
        val week = (weekStart.toEpochDays() / 7).toLong()
        val offset = Math.floorMod(week * 3, pool.size.toLong()).toInt()
        return (0 until PER_WEEK).map { pool[(offset + it * 5) % pool.size] }
    }

    fun find(slug: String): MissionDefinition? = pool.firstOrNull { it.slug == slug }
}
