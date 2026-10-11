package org.mobyle.domain.usecase.profile

import org.mobyle.data.local.user.UserDatabaseDataSource
import org.mobyle.domain.model.MovieShelf

class CreateShelf(private val userDatabaseDataSource: UserDatabaseDataSource) {
    operator fun invoke(
        userId: String,
        name: String,
        description: String?,
        color: String?,
        visibility: String
    ): MovieShelf {
        require(name.isNotBlank()) { "Shelf name cannot be blank" }
        return userDatabaseDataSource.createShelf(userId, name, description, color, visibility)
    }
}
