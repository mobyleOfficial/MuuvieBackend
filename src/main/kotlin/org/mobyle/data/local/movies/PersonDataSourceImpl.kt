package org.mobyle.data.local.movies

import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.innerJoin
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.mobyle.data.local.database.MovieCastTable
import org.mobyle.data.local.database.MoviesTable
import org.mobyle.data.local.database.PeopleTable
import org.mobyle.domain.model.Movie
import org.mobyle.domain.model.Person
import org.mobyle.domain.model.PersonCredit

class PersonDataSourceImpl : PersonDataSource {

    override fun getPersonById(id: Long): Person? {
        return transaction {
            PeopleTable.selectAll()
                .where { PeopleTable.id eq id }
                .firstOrNull()
                ?.let { row ->
                    Person(
                        id = row[PeopleTable.id].value,
                        tmdbPersonId = row[PeopleTable.tmdbId],
                        name = row[PeopleTable.name],
                        profilePath = row[PeopleTable.profilePath]
                    )
                }
        }
    }

    override fun getCreditsForPerson(personId: Long): List<PersonCredit> {
        return transaction {
            (MovieCastTable innerJoin MoviesTable)
                .selectAll()
                .where { MovieCastTable.personId eq personId }
                .orderBy(MoviesTable.releaseDate, SortOrder.DESC_NULLS_LAST)
                .map { row ->
                    PersonCredit(
                        movie = Movie(
                            id = row[MoviesTable.id].value,
                            tmdbId = row[MoviesTable.tmdbId],
                            title = row[MoviesTable.title],
                            posterPath = row[MoviesTable.posterPath],
                            releaseDate = row[MoviesTable.releaseDate],
                            voteAverage = row[MoviesTable.voteAverage]?.toDouble() ?: 0.0
                        ),
                        role = row[MovieCastTable.role],
                        character = row[MovieCastTable.character]
                    )
                }
        }
    }
}
