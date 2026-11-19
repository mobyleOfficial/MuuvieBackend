package org.mobyle.data.local.auth

import kotlinx.datetime.Clock
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.mobyle.data.local.database.UserIdentitiesTable
import org.mobyle.data.local.database.UsersTable
import org.mobyle.domain.model.SocialIdentity

/** Which account a provider account (Apple or Google) signs in to. */
interface UserIdentityDataSource {
    /** External id of the account linked to [identity], or null when the provider account is new. */
    fun findUserExternalId(identity: SocialIdentity): String?

    /** Links [identity] to the account with [userExternalId]. */
    fun link(userExternalId: String, identity: SocialIdentity)
}

class UserIdentityDataSourceImpl : UserIdentityDataSource {

    override fun findUserExternalId(identity: SocialIdentity): String? = transaction {
        (UserIdentitiesTable innerJoin UsersTable)
            .select(UsersTable.externalId)
            .where {
                (UserIdentitiesTable.provider eq identity.provider.wire) and
                    (UserIdentitiesTable.subject eq identity.subject)
            }
            .firstOrNull()
            ?.get(UsersTable.externalId)
    }

    override fun link(userExternalId: String, identity: SocialIdentity) {
        transaction {
            val userDbId = UsersTable.selectAll()
                .where { UsersTable.externalId eq userExternalId }
                .firstOrNull()
                ?.get(UsersTable.id)
                ?: error("No user $userExternalId to link")
            UserIdentitiesTable.insert {
                it[userId] = userDbId
                it[provider] = identity.provider.wire
                it[subject] = identity.subject
                it[email] = identity.email
                it[createdAt] = Clock.System.now()
            }
        }
    }
}
