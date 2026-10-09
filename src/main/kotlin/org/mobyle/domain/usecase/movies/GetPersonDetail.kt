package org.mobyle.domain.usecase.movies

import kotlinx.coroutines.runBlocking
import org.mobyle.domain.model.PersonDetail
import org.mobyle.domain.repository.MoviesRepository

class GetPersonDetail(private val repository: MoviesRepository) {
    operator fun invoke(personId: Long): PersonDetail? = runBlocking {
        repository.getPersonDetail(personId)
    }
}
