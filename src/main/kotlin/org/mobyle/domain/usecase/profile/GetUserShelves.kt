package org.mobyle.domain.usecase.profile

import org.mobyle.data.local.user.UserDatabaseDataSource
import org.mobyle.model.MovieShelfListing

class GetUserShelves(private val userDatabaseDataSource: UserDatabaseDataSource) {
    operator fun invoke(userId: String, page: Int): MovieShelfListing {
        return userDatabaseDataSource.getUserShelves(userId, page)
    }
}
