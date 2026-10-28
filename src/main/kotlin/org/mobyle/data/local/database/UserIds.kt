package org.mobyle.data.local.database

import org.jetbrains.exposed.sql.selectAll

/** Resolves `users.id` from the external id. Must be called inside a transaction. */
fun userDbIdOf(userExternalId: String): Long? =
    UsersTable.selectAll()
        .where { UsersTable.externalId eq userExternalId }
        .firstOrNull()
        ?.get(UsersTable.id)?.value
