package org.mobyle.domain.usecase.profile

import org.mobyle.data.local.user.UserDatabaseDataSource

class AddShelfToLocadora(private val userDatabaseDataSource: UserDatabaseDataSource) {
    operator fun invoke(userId: String, locadoraId: Long, shelfId: Long) {
        userDatabaseDataSource.addShelfToLocadora(userId, locadoraId, shelfId)
    }
}
