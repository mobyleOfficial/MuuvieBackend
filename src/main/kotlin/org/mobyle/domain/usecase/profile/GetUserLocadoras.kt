package org.mobyle.domain.usecase.profile

import org.mobyle.data.local.user.UserDatabaseDataSource
import org.mobyle.model.LocadoraListing

class GetUserLocadoras(private val userDatabaseDataSource: UserDatabaseDataSource) {
    operator fun invoke(userId: String, page: Int): LocadoraListing {
        return userDatabaseDataSource.getUserLocadoras(userId, page)
    }
}
