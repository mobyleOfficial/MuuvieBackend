package org.mobyle.data.local.user

import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inSubQuery
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.transactions.transaction
import org.mobyle.data.local.database.CommentsTable
import org.mobyle.data.local.database.RefreshTokensTable
import org.mobyle.data.local.database.UserIdentitiesTable
import org.mobyle.data.local.database.UserMoviesTable
import org.mobyle.data.local.database.UsersTable
import org.mobyle.data.local.database.WatchLogsTable
import org.mobyle.data.local.database.userDbIdOf
import org.mobyle.domain.model.CommentTarget
import org.mobyle.domain.model.SocialProvider
import org.mobyle.domain.repository.AccountRepository

/** Exposed implementation of [AccountRepository]. */
interface AccountDataSource : AccountRepository

class AccountDataSourceImpl : AccountDataSource {

    override fun providers(userId: String): Set<SocialProvider> = transaction {
        val user = userDbIdOf(userId) ?: return@transaction emptySet()
        UserIdentitiesTable.select(UserIdentitiesTable.provider)
            .where { UserIdentitiesTable.userId eq user }
            .mapNotNull { SocialProvider.fromWire(it[UserIdentitiesTable.provider]) }
            .toSet()
    }

    /**
     * The foreign keys to users cascade (V23), so deleting the row takes everything with it. Comments
     * point at their target without a foreign key, so the ones other people left on this account's
     * sessions and ratings go first; refresh tokens of old sessions are keyed by the external id.
     */
    override fun delete(userId: String): Boolean = transaction {
        val user = userDbIdOf(userId) ?: return@transaction false
        val logs = WatchLogsTable.select(WatchLogsTable.id).where { WatchLogsTable.userId eq user }
        val ratings = UserMoviesTable.select(UserMoviesTable.id).where { UserMoviesTable.userId eq user }
        CommentsTable.deleteWhere {
            ((targetType eq CommentTarget.WATCH_LOG.wireValue) and (targetId inSubQuery logs)) or
                ((targetType eq CommentTarget.RATING.wireValue) and (targetId inSubQuery ratings))
        }
        RefreshTokensTable.deleteWhere { userExternalId eq userId }
        UsersTable.deleteWhere { UsersTable.id eq user } > 0
    }
}
