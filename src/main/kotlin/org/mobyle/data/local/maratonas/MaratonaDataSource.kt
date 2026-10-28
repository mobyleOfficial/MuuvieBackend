package org.mobyle.data.local.maratonas

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inSubQuery
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.notInList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.mobyle.data.local.database.ChallengeLinksTable
import org.mobyle.data.local.database.MaratonaCategoriesTable
import org.mobyle.data.local.database.MaratonaChallengesTable
import org.mobyle.data.local.database.MaratonaLevelsTable
import org.mobyle.data.local.database.MaratonaOwnersTable
import org.mobyle.data.local.database.MaratonaParticipationsTable
import org.mobyle.data.local.database.MaratonaPrizesTable
import org.mobyle.data.local.database.MaratonaTeamsTable
import org.mobyle.data.local.database.MaratonasTable
import org.mobyle.data.local.database.MoviesTable
import org.mobyle.data.local.database.UsersTable
import org.mobyle.data.local.database.userDbIdOf
import org.mobyle.data.local.feed.toUserSummary
import org.mobyle.domain.model.ChallengeCriteria
import org.mobyle.domain.model.ChallengeLink
import org.mobyle.domain.model.Finisher
import org.mobyle.domain.model.Maratona
import org.mobyle.domain.model.MaratonaCategory
import org.mobyle.domain.model.MaratonaChallenge
import org.mobyle.domain.model.MaratonaDraft
import org.mobyle.domain.model.MaratonaFilter
import org.mobyle.domain.model.MaratonaLevel
import org.mobyle.domain.model.MaratonaPrize
import org.mobyle.domain.model.MaratonaSummary
import org.mobyle.domain.model.MaratonaTeam
import org.mobyle.domain.model.Page
import org.mobyle.domain.model.PageRequest
import org.mobyle.domain.model.Participation
import org.mobyle.domain.model.PasswordChange
import org.mobyle.domain.model.RankingEntry
import org.mobyle.domain.model.TeamRankingEntry
import org.mobyle.domain.model.UserSummary
import org.mobyle.domain.model.vocabulary.ChallengeLinkSource
import org.mobyle.domain.model.vocabulary.ChallengeType
import org.mobyle.domain.model.vocabulary.MaratonaStatus
import org.mobyle.domain.repository.MaratonaRepository

/** Exposed implementation of the maratonas; same contract as [MaratonaRepository]. */
interface MaratonaDataSource : MaratonaRepository

class MaratonaDataSourceImpl : MaratonaDataSource {
    private val json = Json { ignoreUnknownKeys = true }

    override fun slugTaken(slug: String): Boolean = transaction {
        !MaratonasTable.selectAll().where { MaratonasTable.slug eq slug }.empty()
    }

    override fun create(ownerId: String, draft: MaratonaDraft, status: MaratonaStatus, passwordHash: String?): Long = transaction {
        val owner = userDbIdOf(ownerId) ?: throw IllegalArgumentException("Unknown user $ownerId")
        val id = MaratonasTable.insertAndGetId {
            it[slug] = draft.slug
            it[MaratonasTable.ownerId] = owner
            it[title] = draft.title
            it[description] = draft.description
            it[logoUrl] = draft.logoUrl
            it[accentColor] = draft.accentColor
            it[startsOn] = draft.startsOn
            it[endsOn] = draft.endsOn
            it[MaratonasTable.status] = status.wireValue
            it[MaratonasTable.passwordHash] = passwordHash
            it[socialLink] = draft.socialLink
            it[templateId] = draft.templateId
        }.value
        syncStructure(id, draft)
        replacePrizes(id, draft)
        id
    }

    override fun update(id: Long, draft: MaratonaDraft, structure: Boolean, password: PasswordChange) {
        transaction {
            MaratonasTable.update({ MaratonasTable.id eq id }) {
                it[title] = draft.title
                it[description] = draft.description
                it[logoUrl] = draft.logoUrl
                it[accentColor] = draft.accentColor
                it[socialLink] = draft.socialLink
                it[updatedAt] = Clock.System.now()
                if (structure) {
                    it[startsOn] = draft.startsOn
                    it[endsOn] = draft.endsOn
                }
                when (password) {
                    PasswordChange.Keep -> Unit
                    PasswordChange.Remove -> it[passwordHash] = null
                    is PasswordChange.Set -> it[passwordHash] = password.hash
                }
            }
            if (structure) syncStructure(id, draft)
            replacePrizes(id, draft)
        }
    }

    override fun get(slug: String): Maratona? = transaction {
        (MaratonasTable innerJoin UsersTable).selectAll()
            .where { MaratonasTable.slug eq slug }
            .firstOrNull()
            ?.let(::toMaratona)
    }

    override fun getById(id: Long): Maratona? = transaction {
        (MaratonasTable innerJoin UsersTable).selectAll()
            .where { MaratonasTable.id eq id }
            .firstOrNull()
            ?.let(::toMaratona)
    }

    override fun passwordHash(id: Long): String? = transaction {
        MaratonasTable.select(MaratonasTable.passwordHash)
            .where { MaratonasTable.id eq id }
            .firstOrNull()
            ?.get(MaratonasTable.passwordHash)
    }

    override fun list(viewerId: String?, filter: MaratonaFilter, page: PageRequest): Page<MaratonaSummary> = transaction {
        val viewer = viewerId?.let { userDbIdOf(it) }
        val participant = filter.participant?.let { userDbIdOf(it) ?: return@transaction Page.of(emptyList(), page, 0) }
        var condition: Op<Boolean> = if (filter.mine) {
            if (viewer == null) return@transaction Page.of(emptyList(), page, 0)
            (MaratonasTable.ownerId eq viewer) or (MaratonasTable.id inSubQuery
                MaratonaOwnersTable.select(MaratonaOwnersTable.maratonaId).where { MaratonaOwnersTable.userId eq viewer })
        } else if (participant != null) {
            MaratonasTable.status neq MaratonaStatus.CANCELLED.wireValue
        } else {
            val statuses = listOfNotNull(
                MaratonaStatus.UPCOMING, MaratonaStatus.ACTIVE, MaratonaStatus.FINISHED.takeIf { filter.includeFinished }
            )
            MaratonasTable.status inList statuses.map { it.wireValue }
        }
        filter.query?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { query ->
            val escaped = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
            condition = condition and (MaratonasTable.title.lowerCase() like "%$escaped%")
        }
        if (participant != null) {
            condition = condition and (MaratonasTable.id inSubQuery MaratonaParticipationsTable
                .select(MaratonaParticipationsTable.maratonaId)
                .where { MaratonaParticipationsTable.userId eq participant })
        }
        filter.movieId?.let { movieId ->
            // A challenge link of the movie, by anyone (B13.5).
            condition = condition and (MaratonasTable.id inSubQuery
                (ChallengeLinksTable innerJoin MaratonaChallengesTable innerJoin MaratonaCategoriesTable)
                    .select(MaratonaCategoriesTable.maratonaId)
                    .where { ChallengeLinksTable.movieId eq movieId })
        }
        if (filter.open) condition = condition and (MaratonasTable.passwordHash.isNull())
        if (filter.trustedOnly) condition = condition and (MaratonasTable.trusted eq true)

        val total = MaratonasTable.selectAll().where { condition }.count().toInt()
        val rows = (MaratonasTable innerJoin UsersTable).selectAll()
            .where { condition }
            .orderBy(MaratonasTable.trusted to SortOrder.DESC, MaratonasTable.startsOn to SortOrder.DESC, MaratonasTable.id to SortOrder.DESC)
            .limit(page.limit, page.offset)
            .toList()
        val ids = rows.map { it[MaratonasTable.id].value }
        val challenges = challengeCounts(ids)
        val participants = participantCounts(ids)
        val joined = viewer?.let {
            MaratonaParticipationsTable.select(MaratonaParticipationsTable.maratonaId)
                .where { (MaratonaParticipationsTable.userId eq it) and (MaratonaParticipationsTable.maratonaId inList ids) }
                .map { row -> row[MaratonaParticipationsTable.maratonaId].value }
                .toSet()
        }.orEmpty()
        val results = rows.map { row ->
            val id = row[MaratonasTable.id].value
            MaratonaSummary(
                id = id,
                slug = row[MaratonasTable.slug],
                title = row[MaratonasTable.title],
                logoUrl = row[MaratonasTable.logoUrl],
                accentColor = row[MaratonasTable.accentColor],
                startsOn = row[MaratonasTable.startsOn],
                endsOn = row[MaratonasTable.endsOn],
                status = status(row),
                hasPassword = row[MaratonasTable.passwordHash] != null,
                trusted = row[MaratonasTable.trusted],
                owner = row.toUserSummary(),
                totalChallenges = challenges[id] ?: 0,
                totalParticipants = participants[id] ?: 0,
                joined = id in joined
            )
        }
        Page.of(results, page, total)
    }

    override fun setStatus(id: Long, status: MaratonaStatus) {
        transaction {
            MaratonasTable.update({ MaratonasTable.id eq id }) {
                it[MaratonasTable.status] = status.wireValue
                it[updatedAt] = Clock.System.now()
            }
        }
    }

    override fun setCoOwners(id: Long, userIds: List<String>) {
        transaction {
            MaratonaOwnersTable.deleteWhere { maratonaId eq id }
            userIds.mapNotNull { userDbIdOf(it) }.forEach { user ->
                MaratonaOwnersTable.insert {
                    it[maratonaId] = id
                    it[userId] = user
                }
            }
        }
    }

    override fun participation(maratonaId: Long, userId: String): Participation? = transaction {
        val user = userDbIdOf(userId) ?: return@transaction null
        MaratonaParticipationsTable.selectAll()
            .where { (MaratonaParticipationsTable.maratonaId eq maratonaId) and (MaratonaParticipationsTable.userId eq user) }
            .firstOrNull()
            ?.toParticipation(userId)
    }

    override fun join(maratonaId: Long, userId: String, levelId: Long, teamId: Long?, queueShelfId: Long): Participation = transaction {
        val user = userDbIdOf(userId) ?: throw IllegalArgumentException("Unknown user $userId")
        val id = MaratonaParticipationsTable.insertAndGetId {
            it[MaratonaParticipationsTable.maratonaId] = maratonaId
            it[MaratonaParticipationsTable.userId] = user
            it[MaratonaParticipationsTable.levelId] = levelId
            it[MaratonaParticipationsTable.teamId] = teamId
            it[MaratonaParticipationsTable.queueShelfId] = queueShelfId
        }
        MaratonaParticipationsTable.selectAll().where { MaratonaParticipationsTable.id eq id }.single().toParticipation(userId)
    }

    override fun leave(participationId: Long) {
        transaction { MaratonaParticipationsTable.deleteWhere { id eq participationId } }
    }

    override fun links(participationId: Long): List<ChallengeLink> = transaction {
        (ChallengeLinksTable innerJoin MoviesTable).selectAll()
            .where { ChallengeLinksTable.participationId eq participationId }
            .orderBy(ChallengeLinksTable.completedAt to SortOrder.ASC, ChallengeLinksTable.id to SortOrder.ASC)
            .map {
                ChallengeLink(
                    challengeId = it[ChallengeLinksTable.challengeId].value,
                    movieId = it[ChallengeLinksTable.movieId].value,
                    tmdbId = it[MoviesTable.tmdbId],
                    title = it[MoviesTable.localTitle] ?: it[MoviesTable.title],
                    posterPath = it[MoviesTable.posterPath],
                    source = ChallengeLinkSource.fromWireValue(it[ChallengeLinksTable.linkSource]) ?: ChallengeLinkSource.MANUAL,
                    completedAt = it[ChallengeLinksTable.completedAt]
                )
            }
    }

    override fun completedChallenges(participationId: Long): Int = transaction {
        ChallengeLinksTable.select(ChallengeLinksTable.challengeId)
            .where { ChallengeLinksTable.participationId eq participationId }
            .withDistinct()
            .count()
            .toInt()
    }

    override fun addLink(
        participationId: Long,
        challengeId: Long,
        movieId: Long,
        watchLogId: Long?,
        source: ChallengeLinkSource
    ): Boolean = transaction {
        ChallengeLinksTable.insertIgnore {
            it[ChallengeLinksTable.participationId] = participationId
            it[ChallengeLinksTable.challengeId] = challengeId
            it[ChallengeLinksTable.movieId] = movieId
            it[ChallengeLinksTable.watchLogId] = watchLogId
            it[ChallengeLinksTable.linkSource] = source.wireValue
        }.insertedCount > 0
    }

    override fun removeLink(participationId: Long, challengeId: Long, movieId: Long): Boolean = transaction {
        ChallengeLinksTable.deleteWhere {
            (ChallengeLinksTable.participationId eq participationId) and (ChallengeLinksTable.challengeId eq challengeId) and
                (ChallengeLinksTable.movieId eq movieId)
        } > 0
    }

    override fun activeParticipations(userId: String, on: LocalDate): List<Pair<Participation, Maratona>> = transaction {
        val user = userDbIdOf(userId) ?: return@transaction emptyList()
        (MaratonaParticipationsTable innerJoin MaratonasTable).selectAll()
            .where {
                (MaratonaParticipationsTable.userId eq user) and (MaratonasTable.status eq MaratonaStatus.ACTIVE.wireValue) and
                    (MaratonasTable.startsOn lessEq on) and (MaratonasTable.endsOn greaterEq on)
            }
            .toList()
            .mapNotNull { row ->
                val maratona = getById(row[MaratonasTable.id].value) ?: return@mapNotNull null
                row.toParticipation(userId) to maratona
            }
    }

    override fun ranking(maratonaId: Long, page: PageRequest): Page<RankingEntry> = transaction {
        val standings = standings(maratonaId)
        val sorted = standings.sortedWith(STANDING_ORDER)
        val results = sorted.drop(page.offset.toInt()).take(page.limit).map { standing ->
            RankingEntry(
                position = 1 + sorted.count { STANDING_ORDER.compare(it, standing) < 0 && !it.tiesWith(standing) },
                user = standing.user,
                teamId = standing.teamId,
                completed = standing.completed,
                lastCompletedAt = standing.lastCompletedAt
            )
        }
        Page.of(results, page, sorted.size)
    }

    override fun teamRanking(maratonaId: Long): List<TeamRankingEntry> = transaction {
        val standings = standings(maratonaId).filter { it.teamId != null }.groupBy { it.teamId!! }
        val teams = teams(maratonaId).map { team ->
            val members = standings[team.id].orEmpty()
            TeamRankingEntry(
                position = 0,
                team = team,
                members = members.size,
                completed = members.sumOf { it.completed },
                lastCompletedAt = members.mapNotNull { it.lastCompletedAt }.maxOrNull()
            )
        }
        val order = compareByDescending<TeamRankingEntry> { it.completed }
            .thenBy(nullsLast()) { it.lastCompletedAt }
        val sorted = teams.sortedWith(order.thenBy { it.team.id })
        sorted.map { entry ->
            entry.copy(position = 1 + sorted.count { order.compare(it, entry) < 0 })
        }
    }

    override fun dueToStart(today: LocalDate): List<Long> = transaction {
        MaratonasTable.select(MaratonasTable.id)
            .where {
                (MaratonasTable.status eq MaratonaStatus.UPCOMING.wireValue) and (MaratonasTable.startsOn lessEq today) and
                    (MaratonasTable.endsOn greaterEq today)
            }
            .map { it[MaratonasTable.id].value }
    }

    override fun dueToFinish(today: LocalDate): List<Long> = transaction {
        MaratonasTable.select(MaratonasTable.id)
            .where {
                (MaratonasTable.status inList listOf(MaratonaStatus.UPCOMING.wireValue, MaratonaStatus.ACTIVE.wireValue)) and
                    (MaratonasTable.endsOn less today)
            }
            .map { it[MaratonasTable.id].value }
    }

    override fun finishers(maratonaId: Long): List<Finisher> = transaction {
        val levels = levels(maratonaId)
        val lowest = levels.minByOrNull { it.minChallenges } ?: return@transaction emptyList()
        standings(maratonaId).mapNotNull { standing ->
            val level = levels.firstOrNull { it.id == standing.levelId } ?: lowest
            if (standing.completed >= level.minChallenges) Finisher(standing.user.id, level, standing.completed) else null
        }
    }

    override fun createdCount(userId: String): Int = transaction {
        val user = userDbIdOf(userId) ?: return@transaction 0
        MaratonasTable.selectAll()
            .where { (MaratonasTable.ownerId eq user) and (MaratonasTable.status neq MaratonaStatus.CANCELLED.wireValue) }
            .count()
            .toInt()
    }

    override fun participationCount(userId: String): Int = transaction {
        val user = userDbIdOf(userId) ?: return@transaction 0
        MaratonaParticipationsTable.selectAll().where { MaratonaParticipationsTable.userId eq user }.count().toInt()
    }

    // ── Helpers (inside a transaction) ──────────────────────────────────────

    private fun toMaratona(row: ResultRow): Maratona {
        val id = row[MaratonasTable.id].value
        val coOwners = (MaratonaOwnersTable innerJoin UsersTable).selectAll()
            .where { MaratonaOwnersTable.maratonaId eq id }
            .orderBy(UsersTable.id)
            .map { it.toUserSummary() }
        val categories = categories(id)
        return Maratona(
            id = id,
            slug = row[MaratonasTable.slug],
            title = row[MaratonasTable.title],
            description = row[MaratonasTable.description],
            logoUrl = row[MaratonasTable.logoUrl],
            accentColor = row[MaratonasTable.accentColor],
            startsOn = row[MaratonasTable.startsOn],
            endsOn = row[MaratonasTable.endsOn],
            status = status(row),
            hasPassword = row[MaratonasTable.passwordHash] != null,
            socialLink = row[MaratonasTable.socialLink],
            trusted = row[MaratonasTable.trusted],
            templateId = row[MaratonasTable.templateId],
            owner = row.toUserSummary(),
            coOwners = coOwners,
            categories = categories,
            levels = levels(id),
            teams = teams(id),
            prizes = MaratonaPrizesTable.selectAll()
                .where { MaratonaPrizesTable.maratonaId eq id }
                .orderBy(MaratonaPrizesTable.id)
                .map { MaratonaPrize(it[MaratonaPrizesTable.id].value, it[MaratonaPrizesTable.title], it[MaratonaPrizesTable.description]) },
            totalChallenges = categories.sumOf { it.challenges.size },
            totalParticipants = participantCounts(listOf(id))[id] ?: 0,
            createdAt = row[MaratonasTable.createdAt]
        )
    }

    private fun categories(maratonaId: Long): List<MaratonaCategory> {
        val categories = MaratonaCategoriesTable.selectAll()
            .where { MaratonaCategoriesTable.maratonaId eq maratonaId }
            .orderBy(MaratonaCategoriesTable.position to SortOrder.ASC, MaratonaCategoriesTable.id to SortOrder.ASC)
            .toList()
        val challenges = MaratonaChallengesTable.selectAll()
            .where { MaratonaChallengesTable.categoryId inList categories.map { it[MaratonaCategoriesTable.id] } }
            .orderBy(MaratonaChallengesTable.position to SortOrder.ASC, MaratonaChallengesTable.id to SortOrder.ASC)
            .map {
                MaratonaChallenge(
                    id = it[MaratonaChallengesTable.id].value,
                    categoryId = it[MaratonaChallengesTable.categoryId].value,
                    description = it[MaratonaChallengesTable.description],
                    type = ChallengeType.fromWireValue(it[MaratonaChallengesTable.type]) ?: ChallengeType.FREE,
                    criteria = it[MaratonaChallengesTable.criteria]?.let { text -> json.decodeFromString(ChallengeCriteria.serializer(), text) },
                    position = it[MaratonaChallengesTable.position],
                    daily = it[MaratonaChallengesTable.daily]
                )
            }
            .groupBy { it.categoryId }
        return categories.map {
            val id = it[MaratonaCategoriesTable.id].value
            MaratonaCategory(
                id = id,
                name = it[MaratonaCategoriesTable.name],
                position = it[MaratonaCategoriesTable.position],
                teamOnly = it[MaratonaCategoriesTable.teamOnly],
                challenges = challenges[id].orEmpty()
            )
        }
    }

    private fun levels(maratonaId: Long): List<MaratonaLevel> = MaratonaLevelsTable.selectAll()
        .where { MaratonaLevelsTable.maratonaId eq maratonaId }
        .orderBy(MaratonaLevelsTable.minChallenges to SortOrder.ASC, MaratonaLevelsTable.id to SortOrder.ASC)
        .map { MaratonaLevel(it[MaratonaLevelsTable.id].value, it[MaratonaLevelsTable.name], it[MaratonaLevelsTable.minChallenges]) }

    private fun teams(maratonaId: Long): List<MaratonaTeam> = MaratonaTeamsTable.selectAll()
        .where { MaratonaTeamsTable.maratonaId eq maratonaId }
        .orderBy(MaratonaTeamsTable.id)
        .map { MaratonaTeam(it[MaratonaTeamsTable.id].value, it[MaratonaTeamsTable.name], it[MaratonaTeamsTable.color]) }

    /** Updates the categories, challenges, levels and teams with an id, inserts the new ones and deletes the rest. */
    private fun syncStructure(maratonaId: Long, draft: MaratonaDraft) {
        val storedCategories = MaratonaCategoriesTable.select(MaratonaCategoriesTable.id)
            .where { MaratonaCategoriesTable.maratonaId eq maratonaId }
            .map { it[MaratonaCategoriesTable.id].value }
            .toSet()
        val keptCategories = draft.categories.mapIndexed { position, category ->
            val categoryId = if (category.id != null && category.id in storedCategories) {
                MaratonaCategoriesTable.update({ MaratonaCategoriesTable.id eq category.id }) {
                    it[name] = category.name
                    it[MaratonaCategoriesTable.position] = position
                    it[teamOnly] = category.teamOnly
                }
                category.id
            } else {
                MaratonaCategoriesTable.insertAndGetId {
                    it[MaratonaCategoriesTable.maratonaId] = maratonaId
                    it[name] = category.name
                    it[MaratonaCategoriesTable.position] = position
                    it[teamOnly] = category.teamOnly
                }.value
            }
            val storedChallenges = MaratonaChallengesTable.select(MaratonaChallengesTable.id)
                .where { MaratonaChallengesTable.categoryId eq categoryId }
                .map { it[MaratonaChallengesTable.id].value }
                .toSet()
            val keptChallenges = category.challenges.mapIndexed { index, challenge ->
                val criteria = challenge.criteria?.let { json.encodeToString(ChallengeCriteria.serializer(), it) }
                if (challenge.id != null && challenge.id in storedChallenges) {
                    MaratonaChallengesTable.update({ MaratonaChallengesTable.id eq challenge.id }) {
                        it[description] = challenge.description
                        it[type] = challenge.type.wireValue
                        it[MaratonaChallengesTable.criteria] = criteria
                        it[MaratonaChallengesTable.position] = index
                        it[daily] = challenge.daily
                    }
                    challenge.id
                } else {
                    MaratonaChallengesTable.insertAndGetId {
                        it[MaratonaChallengesTable.categoryId] = categoryId
                        it[description] = challenge.description
                        it[type] = challenge.type.wireValue
                        it[MaratonaChallengesTable.criteria] = criteria
                        it[MaratonaChallengesTable.position] = index
                        it[daily] = challenge.daily
                    }.value
                }
            }
            MaratonaChallengesTable.deleteWhere {
                (MaratonaChallengesTable.categoryId eq categoryId) and (MaratonaChallengesTable.id notInList keptChallenges)
            }
            categoryId
        }
        MaratonaCategoriesTable.deleteWhere {
            (MaratonaCategoriesTable.maratonaId eq maratonaId) and (MaratonaCategoriesTable.id notInList keptCategories)
        }

        val storedLevels = levels(maratonaId).map { it.id }.toSet()
        val keptLevels = draft.levels.map { level ->
            if (level.id != null && level.id in storedLevels) {
                MaratonaLevelsTable.update({ MaratonaLevelsTable.id eq level.id }) {
                    it[name] = level.name
                    it[minChallenges] = level.minChallenges
                }
                level.id
            } else {
                MaratonaLevelsTable.insertAndGetId {
                    it[MaratonaLevelsTable.maratonaId] = maratonaId
                    it[name] = level.name
                    it[minChallenges] = level.minChallenges
                }.value
            }
        }
        MaratonaLevelsTable.deleteWhere { (MaratonaLevelsTable.maratonaId eq maratonaId) and (MaratonaLevelsTable.id notInList keptLevels) }

        val storedTeams = teams(maratonaId).map { it.id }.toSet()
        val keptTeams = draft.teams.map { team ->
            if (team.id != null && team.id in storedTeams) {
                MaratonaTeamsTable.update({ MaratonaTeamsTable.id eq team.id }) {
                    it[name] = team.name
                    it[color] = team.color
                }
                team.id
            } else {
                MaratonaTeamsTable.insertAndGetId {
                    it[MaratonaTeamsTable.maratonaId] = maratonaId
                    it[name] = team.name
                    it[color] = team.color
                }.value
            }
        }
        MaratonaTeamsTable.deleteWhere { (MaratonaTeamsTable.maratonaId eq maratonaId) and (MaratonaTeamsTable.id notInList keptTeams) }
    }

    private fun replacePrizes(maratonaId: Long, draft: MaratonaDraft) {
        MaratonaPrizesTable.deleteWhere { MaratonaPrizesTable.maratonaId eq maratonaId }
        draft.prizes.forEach { prize ->
            MaratonaPrizesTable.insert {
                it[MaratonaPrizesTable.maratonaId] = maratonaId
                it[title] = prize.title
                it[description] = prize.description
            }
        }
    }

    private fun challengeCounts(maratonaIds: List<Long>): Map<Long, Int> =
        (MaratonaChallengesTable innerJoin MaratonaCategoriesTable)
            .select(MaratonaCategoriesTable.maratonaId)
            .where { MaratonaCategoriesTable.maratonaId inList maratonaIds }
            .map { it[MaratonaCategoriesTable.maratonaId].value }
            .groupingBy { it }
            .eachCount()

    private fun participantCounts(maratonaIds: List<Long>): Map<Long, Int> =
        MaratonaParticipationsTable.select(MaratonaParticipationsTable.maratonaId)
            .where { MaratonaParticipationsTable.maratonaId inList maratonaIds }
            .map { it[MaratonaParticipationsTable.maratonaId].value }
            .groupingBy { it }
            .eachCount()

    /**
     * Every participant with their done challenges. A challenge counts from its first movie,
     * so tying more movies to a done challenge does not move the participant down.
     */
    private fun standings(maratonaId: Long): List<Standing> {
        val participants = (MaratonaParticipationsTable innerJoin UsersTable).selectAll()
            .where { MaratonaParticipationsTable.maratonaId eq maratonaId }
            .toList()
        val firstLinks = ChallengeLinksTable.select(
            ChallengeLinksTable.participationId, ChallengeLinksTable.challengeId, ChallengeLinksTable.completedAt
        )
            .where { ChallengeLinksTable.participationId inList participants.map { it[MaratonaParticipationsTable.id] } }
            .groupBy(
                { it[ChallengeLinksTable.participationId].value to it[ChallengeLinksTable.challengeId].value },
                { it[ChallengeLinksTable.completedAt] }
            )
            .mapValues { (_, times) -> times.min() }
            .entries
            .groupBy({ it.key.first }, { it.value })
        return participants.map { row ->
            val id = row[MaratonaParticipationsTable.id].value
            val done = firstLinks[id].orEmpty()
            Standing(
                user = row.toUserSummary(),
                levelId = row[MaratonaParticipationsTable.levelId]?.value,
                teamId = row[MaratonaParticipationsTable.teamId]?.value,
                completed = done.size,
                lastCompletedAt = done.maxOrNull(),
                joinedAt = row[MaratonaParticipationsTable.joinedAt]
            )
        }
    }

    private fun ResultRow.toParticipation(userId: String) = Participation(
        id = this[MaratonaParticipationsTable.id].value,
        maratonaId = this[MaratonaParticipationsTable.maratonaId].value,
        userId = userId,
        levelId = this[MaratonaParticipationsTable.levelId]?.value,
        teamId = this[MaratonaParticipationsTable.teamId]?.value,
        queueShelfId = this[MaratonaParticipationsTable.queueShelfId]?.value,
        joinedAt = this[MaratonaParticipationsTable.joinedAt]
    )

    private fun status(row: ResultRow) = MaratonaStatus.fromWireValue(row[MaratonasTable.status]) ?: MaratonaStatus.UPCOMING

    private data class Standing(
        val user: UserSummary,
        val levelId: Long?,
        val teamId: Long?,
        val completed: Int,
        val lastCompletedAt: Instant?,
        val joinedAt: Instant
    ) {
        fun tiesWith(other: Standing) = completed == other.completed && lastCompletedAt == other.lastCompletedAt
    }

    private companion object {
        // More challenges first; between equals, who reached the count earlier.
        val STANDING_ORDER: Comparator<Standing> = compareByDescending<Standing> { it.completed }
            .thenBy(nullsLast()) { it.lastCompletedAt }
            .thenBy { it.joinedAt }
    }
}
