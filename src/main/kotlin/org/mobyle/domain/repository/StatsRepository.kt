package org.mobyle.domain.repository

import org.mobyle.domain.model.LogFact
import org.mobyle.domain.model.MovieStatFacts
import org.mobyle.domain.model.RatingFact
import org.mobyle.domain.model.vocabulary.Visibility

/**
 * What the statistics, the retrospective and the yearly goals read (B9). Users are identified
 * by their external id; only sessions with one of the [visibilities] count.
 */
interface StatsRepository {
    fun years(ownerId: String, visibilities: Set<Visibility>): List<Int>

    fun logs(ownerId: String, year: Int, visibilities: Set<Visibility>): List<LogFact>

    fun movies(movieIds: Collection<Long>): Map<Long, MovieStatFacts>

    /** Ratings and favorites of [movieIds]; empty when the viewer cannot see the owner's Já assisti. */
    fun ratings(ownerId: String, movieIds: Collection<Long>, visibilities: Set<Visibility>): Map<Long, RatingFact>

    /** Distinct movies with a completed session in [year], for the goal progress. */
    fun finishedMovies(ownerId: String, year: Int, visibilities: Set<Visibility>): Int

    fun maratonasFinished(ownerId: String, year: Int): Int

    fun cineclubesJoined(ownerId: String, year: Int): Int

    fun goal(ownerId: String, year: Int): Int?

    fun setGoal(ownerId: String, year: Int, targetMovies: Int)

    fun removeGoal(ownerId: String, year: Int): Boolean
}
