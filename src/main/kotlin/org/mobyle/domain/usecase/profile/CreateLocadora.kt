package org.mobyle.domain.usecase.profile

import org.mobyle.data.local.user.UserDatabaseDataSource
import org.mobyle.domain.model.Locadora

class CreateLocadora(private val userDatabaseDataSource: UserDatabaseDataSource) {
    operator fun invoke(
        userId: String,
        name: String,
        description: String?,
        visibility: String
    ): Locadora {
        require(name.isNotBlank()) { "Locadora name cannot be blank" }
        return userDatabaseDataSource.createLocadora(userId, name, description, visibility)
    }
}
