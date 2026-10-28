package org.mobyle.data.remote.articles

import io.mockk.mockk
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.jetbrains.exposed.sql.Database
import org.mobyle.data.local.database.DatabaseMigrations
import org.mobyle.data.local.shelves.ShelfDataSourceImpl
import org.mobyle.data.local.user.UserDatabaseDataSourceImpl
import org.mobyle.domain.model.ArticleMovieState
import org.mobyle.domain.model.PageRequest
import org.mobyle.domain.model.User
import org.mobyle.domain.model.vocabulary.SystemShelfType
import org.mobyle.domain.model.vocabulary.Visibility
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ArticlesDataSourceImplTest {

    private lateinit var dataSource: DataSource
    private val articles = ArticlesDataSourceImpl()
    private val shelves = ShelfDataSourceImpl()
    private val users = UserDatabaseDataSourceImpl()
    private val page = PageRequest(1, 50)

    @BeforeTest
    fun setUp() {
        val name = "articles_${counter.incrementAndGet()}"
        postgres.postgresDatabase.connection.use { it.createStatement().execute("CREATE DATABASE $name") }
        dataSource = postgres.getDatabase("postgres", name)
        DatabaseMigrations.migrate(dataSource)
        Database.connect(dataSource)

        users.save(User(id = "ana", email = "ana@x.com", username = "ana", avatar = null, bio = null, createdAt = ""))
        execute(
            """
            INSERT INTO movies (id, tmdb_id, title, local_title) VALUES (10, 100, 'I''m Still Here', 'Ainda Estou Aqui'), (11, 101, 'Dune', 'Duna');
            INSERT INTO articles (id, source_url, title, summary, content, source, published_at, scraped_at) VALUES
                (1, 'https://g1/1', 'G1 antigo', '', '', 'G1 Cinema', '2026-10-01', now()),
                (2, 'https://om/2', 'Omelete', '', '', 'Omelete', '2026-10-02', now()),
                (3, 'https://g1/3', 'G1 novo', '', '', 'G1 Cinema', '2026-10-03', now());
            """
        )
    }

    @Test
    fun `the v1 page filters by source, newest first, and lists the sources`() {
        assertEquals(listOf(3L, 1L), articles.getArticlesPage("G1 Cinema", page).results.map { it.id })
        assertEquals(3, articles.getArticlesPage(null, page).totalResults)
        assertEquals(listOf("G1 Cinema", "Omelete"), articles.getSources())
    }

    @Test
    fun `linking saves the movies, marks the article and replaces earlier links`() {
        assertEquals(listOf(3L, 2L, 1L), articles.articlesToLink(10).map { it.id })

        articles.saveMovieLinks(3, mapOf(10L to 0.8f, 11L to 1.0f))
        articles.saveMovieLinks(2, emptyMap())

        assertEquals(listOf(1L), articles.articlesToLink(10).map { it.id })
        val movies = articles.articleMovies(listOf(3, 2))
        assertEquals(listOf("Duna", "Ainda Estou Aqui"), movies[3]?.map { it.title })
        assertEquals(null, movies[2])

        articles.saveMovieLinks(3, mapOf(10L to 1.0f))
        assertEquals(listOf(10L), articles.articleMovies(listOf(3))[3]?.map { it.movieId })
    }

    @Test
    fun `movie states say whether the viewer has the movie on Quero ver or Ja assisti`() {
        val shelvesOfAna = shelves.getUserShelves("ana", Visibility.entries.toSet(), page).results
        shelves.addMovie(shelvesOfAna.single { it.systemType == SystemShelfType.WATCHLIST }, 10)
        shelves.addMovie(shelvesOfAna.single { it.systemType == SystemShelfType.WATCHED }, 11)

        assertEquals(
            mapOf(10L to ArticleMovieState(inQueroVer = true, watched = false), 11L to ArticleMovieState(false, true)),
            articles.movieStates("ana", listOf(10, 11))
        )
    }

    private fun execute(sql: String) = dataSource.connection.use { it.createStatement().execute(sql) }

    companion object {
        private val postgres: EmbeddedPostgres by lazy { EmbeddedPostgres.start() }
        private val counter = AtomicInteger()
    }
}
