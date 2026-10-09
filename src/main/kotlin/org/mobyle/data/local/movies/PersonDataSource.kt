package org.mobyle.data.local.movies

import org.mobyle.domain.model.Person
import org.mobyle.domain.model.PersonCredit

interface PersonDataSource {
    fun getPersonById(id: Long): Person?
    fun getCreditsForPerson(personId: Long): List<PersonCredit>
}
