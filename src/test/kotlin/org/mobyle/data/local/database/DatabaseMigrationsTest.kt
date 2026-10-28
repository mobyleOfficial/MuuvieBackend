package org.mobyle.data.local.database

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.math.BigDecimal
import java.sql.Connection
import javax.sql.DataSource
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DatabaseMigrationsTest {

    private lateinit var postgres: EmbeddedPostgres
    private lateinit var dataSource: DataSource

    @BeforeTest
    fun setUp() {
        postgres = EmbeddedPostgres.start()
        dataSource = postgres.postgresDatabase
    }

    @AfterTest
    fun tearDown() {
        postgres.close()
    }

    @Test
    fun `empty database migrates to a schema that matches the Exposed tables`() {
        DatabaseMigrations.migrate(dataSource)
        Database.connect(dataSource)

        val missingColumns = transaction { SchemaUtils.addMissingColumnsStatements(*DatabaseTables.all) }
        assertEquals(emptyList(), missingColumns)
        assertTrue(appliedVersions().containsAll(listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15", "16", "17", "18", "19")))
    }

    @Test
    fun `legacy lists become shelves and every user gets system shelves filled from the status`() {
        execute(legacySchema())
        seedLegacyData()
        execute(
            """
            INSERT INTO user_lists (id, user_id, name, is_public, visibility, created_at) VALUES
                (1, 1, 'Old', true, 'public', '2026-01-01'),
                (2, 1, 'Hidden', false, 'private', '2026-02-01'),
                (3, 2, 'Friends', true, 'mutuals', '2026-03-01');
            INSERT INTO user_list_items (list_id, movie_id, position, added_at) VALUES (1, 10, 0, now()), (1, 11, 1, now());
            INSERT INTO locadoras (id, user_id, name, created_at) VALUES (1, 1, 'Loc', now());
            INSERT INTO locadora_shelves (locadora_id, shelf_id, position, added_at) VALUES (1, 1, 0, now());
            """
        )

        DatabaseMigrations.migrate(dataSource)

        val visibilities = query("SELECT id, visibility FROM shelves WHERE system_type IS NULL ORDER BY id")
            .associate { it["id"] to it["visibility"] }
        assertEquals(mapOf<Any?, Any?>(1L to "public", 2L to "secret", 3L to "mutual"), visibilities)
        assertEquals(2L, count("SELECT count(*) FROM shelf_items WHERE shelf_id = 1"))
        assertEquals(1L, count("SELECT count(*) FROM locadora_shelves WHERE shelf_id = 1"))

        val systemShelves = query(
            "SELECT user_id, system_type, name, visibility, position FROM shelves WHERE system_type IS NOT NULL ORDER BY user_id, position"
        )
        assertEquals(4, systemShelves.size)
        assertEquals(listOf("watched", "watchlist"), systemShelves.filter { it["user_id"] == 1L }.map { it["system_type"] })
        assertEquals("Já assisti", systemShelves.first()["name"])
        assertTrue(systemShelves.all { it["visibility"] == "public" })

        // ana watched 10 and 11 (merged by V3); bia wants to watch 10.
        assertEquals(listOf(10L, 11L), query(systemItems(1, "watched")).map { it["movie_id"] })
        assertEquals(emptyList<Any?>(), query(systemItems(1, "watchlist")).map { it["movie_id"] })
        assertEquals(listOf(10L), query(systemItems(2, "watchlist")).map { it["movie_id"] })
        // The user's own shelves come after the system ones on the profile, newest first.
        assertEquals(
            listOf(2L, 1L),
            query("SELECT id FROM shelves WHERE user_id = 1 AND system_type IS NULL ORDER BY position").map { it["id"] }
        )
    }

    @Test
    fun `ten-scale ratings are halved, the sentiment is derived and likes become favorites`() {
        execute(legacySchema())
        seedLegacyData()
        execute(
            """
            INSERT INTO movies (id, title) VALUES (12, 'C');
            INSERT INTO user_movies (id, user_id, movie_id, status, rating, rewatches, is_favorite, source, created_at, updated_at) VALUES
                (6, 2, 11, 'dropped', 7.0, 0, false, 'manual', now(), now()),
                (7, 2, 12, 'watched', 9.5, 1, false, 'manual', now(), now());
            INSERT INTO movie_likes (user_external_id, movie_id, created_at) VALUES ('ext-1', 12, now());
            """
        )

        migrateTo("16")

        val rows = query(
            """SELECT user_id, movie_id, rating::text AS rating, sentiment, status, is_favorite, current_cycle
               FROM user_movies ORDER BY user_id, movie_id"""
        ).associateBy { it["user_id"] to it["movie_id"] }
        fun row(userId: Long, movieId: Long) = rows.getValue(userId to movieId)

        assertEquals("4.0", row(1, 10)["rating"])
        assertEquals("liked", row(1, 10)["sentiment"])
        assertEquals("not_for_me", row(1, 11)["sentiment"])
        // 7/10 and 9.5/10 keep their order: 3.5 and 5 stars.
        assertEquals("3.5", row(2, 11)["rating"])
        assertEquals("liked", row(2, 11)["sentiment"])
        assertEquals("5.0", row(2, 12)["rating"])
        assertEquals("loved", row(2, 12)["sentiment"])
        assertEquals("abandoned", row(2, 11)["status"])
        assertEquals(2, row(2, 12)["current_cycle"])
        // Likes: on an existing row, or a new row with no situation.
        assertEquals(true, row(2, 10)["is_favorite"])
        assertEquals(true, row(1, 12)["is_favorite"])
        assertEquals("none", row(1, 12)["status"])
        assertNull(row(1, 12)["sentiment"])
    }

    @Test
    fun `star ratings are deleted, the heart and the situations become labels`() {
        migrateTo("16")
        execute(
            """
            INSERT INTO users (id, external_id, username, created_at) VALUES (1, 'ext-1', 'ana', now());
            INSERT INTO movies (id, title) VALUES (10, 'A'), (11, 'B'), (12, 'C'), (13, 'D');
            INSERT INTO user_movies (id, user_id, movie_id, status, rating, review, rated_at, is_favorite, source, created_at, updated_at) VALUES
                (1, 1, 10, 'watched', 4.5, 'Bom', now(), false, 'manual', now(), now()),
                (2, 1, 11, 'watched', 3.0, NULL, now(), true, 'manual', now(), now()),
                (3, 1, 12, 'on_hold', NULL, NULL, NULL, false, 'manual', now(), now()),
                (4, 1, 13, 'abandoned', 2.0, NULL, now(), false, 'manual', now(), now());
            INSERT INTO comments (user_id, target_type, target_id, body, created_at) VALUES (1, 'rating', 1, 'Oi', now());
            INSERT INTO activities (actor_id, type, subject_type, subject_id, movie_id, payload) VALUES
                (1, 'avaliacao', 'movie', 10, 10, '{"movie":{"id":10},"rating":{"stars":4.5}}'),
                (1, 'concluiu_filme', 'movie', 11, 11, '{"movie":{"id":11},"log":{"id":1},"rating":{"stars":3.0}}');
            """
        )

        migrateTo("17")

        val rows = query("SELECT movie_id, status, rating, review FROM user_movies ORDER BY movie_id").associateBy { it["movie_id"] }
        assertNull(rows.getValue(10L)["rating"])
        assertNull(rows.getValue(10L)["review"])
        assertEquals("favorite", rows.getValue(11L)["rating"])
        assertEquals("watched", rows.getValue(11L)["status"])
        assertEquals("on_hold", rows.getValue(12L)["rating"])
        assertEquals("none", rows.getValue(12L)["status"])
        assertEquals("abandoned", rows.getValue(13L)["rating"])
        assertEquals(0L, count("SELECT count(*) FROM comments"))
        val activities = query("SELECT type, payload FROM activities")
        assertEquals(listOf("concluiu_filme"), activities.map { it["type"] })
        assertTrue("rating" !in (activities.single()["payload"] as String))
        assertEquals(
            emptyList(),
            query("SELECT column_name FROM information_schema.columns WHERE table_name = 'user_movies' AND column_name IN ('is_favorite', 'sentiment')")
        )
    }

    private fun migrateTo(version: String) {
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
            .baselineOnMigrate(true).baselineVersion("0").target(version).load().migrate()
    }

    private fun systemItems(userId: Int, type: String) =
        """SELECT i.movie_id FROM shelf_items i JOIN shelves s ON s.id = i.shelf_id
           WHERE s.user_id = $userId AND s.system_type = '$type' ORDER BY i.movie_id"""

    @Test
    fun `the legacy likes and the feature flags are dropped`() {
        DatabaseMigrations.migrate(dataSource)

        val left = query(
            """SELECT table_name FROM information_schema.tables
               WHERE table_name IN ('movie_likes', 'review_likes', 'feature_flags')"""
        )
        assertEquals(emptyList(), left)
    }

    @Test
    fun `migrating twice is a no-op`() {
        DatabaseMigrations.migrate(dataSource)
        val firstRun = appliedVersions()

        DatabaseMigrations.migrate(dataSource)

        assertEquals(firstRun, appliedVersions())
    }

    @Test
    fun `legacy database keeps its data, gets user ids and one user_movies row per movie`() {
        execute(legacySchema())
        seedLegacyData()

        migrateTo("16")

        val rows = query(
            """SELECT id, source, rating, review, is_favorite, rewatches, watched_at::text
               FROM user_movies WHERE user_id = 1 AND movie_id = 10"""
        )
        assertEquals(1, rows.size)
        val merged = rows.single()
        assertEquals(1L, merged["id"]) // the manual row is the keeper
        assertEquals("manual", merged["source"])
        assertEquals(BigDecimal("4.0"), merged["rating"]) // manual value wins
        assertEquals("manual review", merged["review"])
        assertEquals(true, merged["is_favorite"]) // OR of both rows
        assertEquals(3, merged["rewatches"]) // 1 + 2
        assertEquals("2025-05-01 00:00:00", merged["watched_at"]) // earliest

        // Empty manual field is filled from the import.
        val other = query("SELECT review FROM user_movies WHERE user_id = 1 AND movie_id = 11").single()
        assertEquals("imported review", other["review"])

        assertEquals(3L, count("SELECT count(*) FROM user_movies"))
        assertEquals(listOf(1L), query("SELECT user_movie_id FROM movie_tags").map { it["user_movie_id"] })

        assertEquals(1L, query("SELECT user_id FROM refresh_tokens").single()["user_id"])
    }

    @Test
    fun `usernames that fit the handle format become handles, the rest stay without one`() {
        execute(legacySchema())
        execute(
            """
            INSERT INTO users (id, external_id, username, created_at) VALUES
                (1, 'ext-1', 'Cinefila', now()), (2, 'ext-2', 'ana', now()),
                (3, 'ext-3', 'joao silva', now()), (4, 'ext-4', 'Duplo.x', now()), (5, 'ext-5', 'duplo.X', now());
            """
        )

        DatabaseMigrations.migrate(dataSource)

        val handles = query("SELECT id, handle FROM users ORDER BY id").associate { it["id"] to it["handle"] }
        // Too short, with a space, or colliding once lowercased: no handle.
        assertEquals(mapOf<Any?, Any?>(1L to "cinefila", 2L to null, 3L to null, 4L to null, 5L to null), handles)
    }

    @Test
    fun `the feed starts with the sessions, ratings, shelves and locadoras made in the app`() {
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target("9").load().migrate()
        execute(
            """
            INSERT INTO users (id, external_id, username, created_at) VALUES (1, 'ext-1', 'ana', now());
            INSERT INTO movies (id, title, tmdb_id, poster_path) VALUES (10, 'Halloween', 100, '/h.jpg');
            INSERT INTO shelves (id, user_id, name, visibility, system_type, created_at) VALUES
                (1, 1, 'Já assisti', 'mutual', 'watched', now()),
                (2, 1, 'Giallo', 'public', NULL, '2026-01-01'),
                (3, 1, 'Do Filmow', 'public', NULL, now());
            UPDATE shelves SET import_source = 'filmow' WHERE id = 3;
            INSERT INTO locadoras (id, user_id, name, visibility, created_at) VALUES (1, 1, 'Terror', 'secret', now());
            INSERT INTO user_movies (user_id, movie_id, status, rating, rated_at, review, source, created_at, updated_at) VALUES
                (1, 10, 'watched', 4.5, '2026-02-01', 'Clássico', 'manual', now(), now());
            INSERT INTO watch_logs (user_id, movie_id, watched_on, mode, completed, visibility, "comment", import_source, created_at, updated_at) VALUES
                (1, 10, '2026-02-01', 'watched', true, 'public', 'De novo', NULL, '2026-02-01', '2026-02-01'),
                (1, 10, '2020-01-01', 'watched', true, 'public', NULL, 'filmow', now(), now());
            """
        )

        migrateTo("16")

        val activities = query("SELECT type, visibility, subject_type, subject_id, movie_id, payload FROM activities ORDER BY type")
        assertEquals(listOf("avaliacao", "concluiu_filme", "nova_estante", "nova_locadora"), activities.map { it["type"] })
        val rating = activities[0]
        assertEquals("mutual", rating["visibility"]) // as visible as Já assisti
        assertTrue((rating["payload"] as String).contains("\"sentiment\" : \"loved\""))
        assertEquals(10L, activities[1]["movie_id"])
        assertTrue((activities[1]["payload"] as String).contains("De novo"))
        assertEquals(2L, activities[2]["subject_id"])
        assertEquals("secret", activities[3]["visibility"])
    }

    @Test
    fun `older legacy database gets the tables and columns it is missing`() {
        execute(legacySchema())
        execute(
            """
            DROP TABLE locadora_shelves; DROP TABLE locadoras;
            ALTER TABLE movies DROP COLUMN trailer_key;
            ALTER TABLE user_lists DROP COLUMN color, DROP COLUMN visibility;
            """
        )

        DatabaseMigrations.migrate(dataSource)
        Database.connect(dataSource)

        val missingColumns = transaction { SchemaUtils.addMissingColumnsStatements(*DatabaseTables.all) }
        assertEquals(emptyList(), missingColumns)
        assertEquals(0L, count("SELECT count(*) FROM locadoras"))
    }

    private fun legacySchema(): String =
        javaClass.getResource("/db/legacy-exposed-schema.sql")!!.readText()

    private fun seedLegacyData() = execute(
        """
        INSERT INTO users (id, external_id, username, created_at) VALUES
            (1, 'ext-1', 'ana', now()), (2, 'ext-2', 'bia', now());
        INSERT INTO movies (id, title) VALUES (10, 'A'), (11, 'B');
        INSERT INTO user_movies (id, user_id, movie_id, status, rating, review, rewatches, watched_at, is_favorite, source, created_at, updated_at) VALUES
            (1, 1, 10, 'watched', 4.0, 'manual review', 1, '2026-01-02', false, 'manual', '2026-01-02', '2026-01-02'),
            (2, 1, 10, 'watched', 3.0, NULL, 2, '2025-05-01', true, 'filmow', '2025-05-01', '2026-03-01'),
            (3, 1, 11, 'watched', NULL, NULL, 0, NULL, false, 'manual', '2026-01-01', '2026-01-01'),
            (4, 1, 11, 'watched', 2.0, 'imported review', 0, NULL, false, 'filmow', '2025-01-01', '2025-01-01'),
            (5, 2, 10, 'want_to_watch', NULL, NULL, 0, NULL, false, 'filmow', now(), now());
        INSERT INTO tags (id, user_id, name) VALUES (1, 1, 'cult');
        INSERT INTO movie_tags (tag_id, user_movie_id) VALUES (1, 2);
        INSERT INTO refresh_tokens (user_external_id, token_hash, expires_at, created_at) VALUES ('ext-1', 'h1', 0, now());
        INSERT INTO movie_likes (user_external_id, movie_id, created_at) VALUES ('ext-2', 10, now());
        INSERT INTO review_likes (user_external_id, review_id, created_at) VALUES ('ghost', 'r1', now());
        SELECT setval('users_id_seq', 10); SELECT setval('movies_id_seq', 20); SELECT setval('user_movies_id_seq', 10);
        """
    )

    private fun appliedVersions(): List<String> =
        Flyway.configure().dataSource(dataSource).load().info().applied()
            .filter { it.state.isApplied }
            .mapNotNull { it.version?.version }

    private fun execute(sql: String) = withConnection { it.createStatement().use { stmt -> stmt.execute(sql) } }

    private fun count(sql: String): Long = query(sql).single().values.single() as Long

    private fun query(sql: String): List<Map<String, Any?>> = withConnection { connection ->
        connection.createStatement().use { stmt ->
            stmt.executeQuery(sql).use { rs ->
                val columns = (1..rs.metaData.columnCount).map { rs.metaData.getColumnLabel(it) }
                buildList { while (rs.next()) add(columns.associateWith { rs.getObject(it) }) }
            }
        }
    }

    private fun <T> withConnection(block: (Connection) -> T): T =
        dataSource.connection.use { connection ->
            connection.autoCommit = true
            block(connection)
        }
}
