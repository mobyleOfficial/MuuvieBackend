package org.mobyle.domain.usecase.profile

import org.mobyle.data.local.user.UserDatabaseDataSource

class AddMovieToShelf(private val userDatabaseDataSource: UserDatabaseDataSource) {
    operator fun invoke(userId: String, shelfId: Long, movieId: Long) {
        userDatabaseDataSource.addMovieToList(userId, shelfId, movieId)
    }
}
