package org.mobyle.data.repository

import org.mobyle.data.local.user.AccountDataSource
import org.mobyle.data.local.user.UserLocalDataSource
import org.mobyle.domain.repository.AccountRepository

class AccountRepositoryImpl(
    private val accountDataSource: AccountDataSource,
    private val userLocalDataSource: UserLocalDataSource
) : AccountRepository by accountDataSource {

    override fun delete(userId: String): Boolean =
        accountDataSource.delete(userId).also { userLocalDataSource.removeUser(userId) }
}
