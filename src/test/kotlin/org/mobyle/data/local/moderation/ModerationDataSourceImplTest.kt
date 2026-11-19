package org.mobyle.data.local.moderation

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import kotlinx.serialization.json.buildJsonObject
import org.jetbrains.exposed.sql.Database
import org.mobyle.data.local.database.DatabaseMigrations
import org.mobyle.data.local.feed.ActivityDataSourceImpl
import org.mobyle.data.local.feed.ConversationDataSourceImpl
import org.mobyle.data.local.feed.FollowDataSourceImpl
import org.mobyle.data.local.user.AccountDataSourceImpl
import org.mobyle.data.local.user.UserDatabaseDataSourceImpl
import org.mobyle.domain.model.ActivityDraft
import org.mobyle.domain.model.PageRequest
import org.mobyle.domain.model.ReportDraft
import org.mobyle.domain.model.ReportReason
import org.mobyle.domain.model.ReportTarget
import org.mobyle.domain.model.SocialProvider
import org.mobyle.domain.model.User
import org.mobyle.domain.model.vocabulary.ActivitySubject
import org.mobyle.domain.model.vocabulary.ActivityType
import org.mobyle.domain.model.vocabulary.FeedScope
import org.mobyle.domain.model.vocabulary.Visibility
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModerationDataSourceImplTest {

    private lateinit var dataSource: DataSource
    private val moderation = ModerationDataSourceImpl()
    private val accounts = AccountDataSourceImpl()
    private val follows = FollowDataSourceImpl()
    private val activities = ActivityDataSourceImpl()
    private val conversations = ConversationDataSourceImpl()
    private val users = UserDatabaseDataSourceImpl()

    @BeforeTest
    fun setUp() {
        val name = "moderation_${counter.incrementAndGet()}"
        postgres.postgresDatabase.connection.use { it.createStatement().execute("CREATE DATABASE $name") }
        dataSource = postgres.getDatabase("postgres", name)
        DatabaseMigrations.migrate(dataSource)
        Database.connect(dataSource)
        listOf("ana", "bia", "caio").forEach {
            users.save(User(id = it, email = "$it@x.com", username = it, avatar = null, bio = null, createdAt = ""))
        }
        execute("INSERT INTO movies (id, tmdb_id, title) VALUES (10, 100, 'Halloween');")
    }

    // ── Blocks ──────────────────────────────────────────────────────────────

    @Test
    fun `a block removes the follows both ways and is idempotent`() {
        follows.follow("ana", "bia")
        follows.follow("bia", "ana")

        assertTrue(moderation.block("ana", "bia"))
        assertFalse(moderation.block("ana", "bia"))

        assertFalse(follows.status("ana", "bia").following)
        assertFalse(follows.status("ana", "bia").followedBy)
        assertTrue(moderation.blocks("ana", "bia"))
        assertFalse(moderation.blocks("bia", "ana"))
        assertTrue(moderation.blockedEitherWay("bia", "ana"))
        assertEquals(setOf("bia"), moderation.blockedIds("ana"))
        assertEquals(setOf("ana"), moderation.blockedIds("bia"))
        assertEquals(listOf("bia"), moderation.blockedUsers("ana", PageRequest(1, 20)).results.map { it.user.id })

        assertTrue(moderation.unblock("ana", "bia"))
        assertFalse(moderation.unblock("ana", "bia"))
        assertFalse(moderation.blockedEitherWay("ana", "bia"))
    }

    @Test
    fun `blocked people leave each other's movie feed, user search and conversation list`() {
        publish("bia", movieId = 10)
        publish("caio", movieId = 10)
        val conversation = conversations.getOrCreateConversation("ana", "bia")

        moderation.block("bia", "ana")

        val movieFeed = activities.movieActivities("ana", 10, FeedScope.ALL, null, null, 20).results
        assertEquals(listOf("caio"), movieFeed.map { it.actor.id })
        // The one who blocked does not see the other either.
        publish("ana", movieId = 10)
        assertEquals(setOf("caio"), activities.movieActivities("bia", 10, FeedScope.ALL, null, null, 20).results.map { it.actor.id }.toSet() - "bia")

        assertEquals(emptyList(), users.searchUsers("bi", "ana").map { it.id })
        assertEquals(listOf("caio"), users.searchUsers("ca", "ana").map { it.id })

        assertTrue(conversations.conversations("ana", null, 20).results.none { it.id == conversation })
    }

    // ── Reports ─────────────────────────────────────────────────────────────

    @Test
    fun `a report finds who is behind the target and survives the reporter's deletion`() {
        val activity = publish("bia")
        assertEquals("bia", moderation.ownerOf(ReportTarget.ACTIVITY, activity.toString()))
        assertEquals("bia", moderation.ownerOf(ReportTarget.USER, "bia"))
        assertNull(moderation.ownerOf(ReportTarget.ACTIVITY, "999"))
        assertNull(moderation.ownerOf(ReportTarget.SHELF, "not-a-number"))

        val id = moderation.report(ReportDraft("ana", "bia", ReportTarget.ACTIVITY, activity.toString(), ReportReason.SPAM, "spam"))
        accounts.delete("ana")

        assertEquals(1L, count("SELECT count(*) FROM reports WHERE id = $id AND reporter_id IS NULL AND status = 'open'"))
    }

    // ── Account deletion ────────────────────────────────────────────────────

    @Test
    fun `deleting an account removes everything it made and the comments on it, and keeps everyone else's`() {
        val ana = "(SELECT id FROM users WHERE external_id = 'ana')"
        val bia = "(SELECT id FROM users WHERE external_id = 'bia')"
        execute(
            """
            INSERT INTO user_identities (user_id, provider, subject, created_at) VALUES ($ana, 'apple', 'apple-ana', now());
            INSERT INTO shelves (id, user_id, name, created_at) VALUES (500, $ana, 'Terror', now()), (501, $bia, 'Comédia', now());
            INSERT INTO shelf_items (shelf_id, movie_id, position, added_at) VALUES (500, 10, 0, now()), (501, 10, 0, now());
            INSERT INTO user_movies (id, user_id, movie_id, status, source, created_at, updated_at)
                VALUES (600, $ana, 10, 'watched', 'app', now(), now());
            INSERT INTO tags (id, user_id, name) VALUES (700, $ana, 'slasher');
            INSERT INTO movie_tags (tag_id, user_movie_id) VALUES (700, 600);
            INSERT INTO watch_logs (id, user_id, movie_id, watched_on, mode, created_at, updated_at)
                VALUES (800, $ana, 10, '2026-10-01', 'watched', now(), now());
            INSERT INTO locadoras (id, user_id, name, created_at) VALUES (900, $ana, 'Loc', now());
            INSERT INTO locadora_shelves (locadora_id, shelf_id, position, added_at) VALUES (900, 500, 0, now());
            INSERT INTO comments (id, user_id, target_type, target_id, body, created_at) VALUES
                (1000, $bia, 'watch_log', 800, 'on ana''s log', now()),
                (1001, $ana, 'watch_log', 999, 'ana elsewhere', now()),
                (1002, $bia, 'rating', 600, 'on ana''s rating', now()),
                (1003, $bia, 'watch_log', 999, 'bia elsewhere', now());
            INSERT INTO refresh_tokens (user_external_id, token_hash, expires_at, created_at) VALUES ('ana', 'h', 9999999999, now());
            """
        )
        follows.follow("ana", "bia")
        follows.follow("bia", "ana")
        moderation.block("caio", "ana")
        publish("ana")
        conversations.getOrCreateConversation("ana", "bia")
        assertEquals(setOf(SocialProvider.APPLE), accounts.providers("ana"))

        assertTrue(accounts.delete("ana"))
        assertFalse(accounts.delete("ana"))

        assertEquals(0L, count("SELECT count(*) FROM users WHERE external_id = 'ana'"))
        assertEquals(listOf(501L), query("SELECT id FROM shelves WHERE id IN (500, 501)"))
        assertEquals(1L, count("SELECT count(*) FROM shelf_items"))
        listOf("user_movies", "tags", "movie_tags", "watch_logs", "locadoras", "locadora_shelves", "user_identities",
            "refresh_tokens", "user_follows", "user_blocks", "activities", "conversations").forEach {
            assertEquals(0L, count("SELECT count(*) FROM $it"), it)
        }
        assertEquals(listOf(1003L), query("SELECT id FROM comments"))
        assertEquals(1L, count("SELECT count(*) FROM users WHERE external_id = 'bia'"))
    }

    private fun publish(actor: String, movieId: Long? = null): Long = activities.insert(
        ActivityDraft(actor, ActivityType.NEW_SHELF, Visibility.PUBLIC, ActivitySubject.SHELF, 1, movieId, buildJsonObject { })
    )

    private fun execute(sql: String) = dataSource.connection.use { it.createStatement().execute(sql) }

    private fun query(sql: String): List<Long> = dataSource.connection.use { connection ->
        connection.createStatement().executeQuery(sql).use { rows ->
            buildList { while (rows.next()) add(rows.getLong(1)) }
        }
    }

    private fun count(sql: String): Long = query(sql).single()

    companion object {
        private val postgres: EmbeddedPostgres by lazy { EmbeddedPostgres.start() }
        private val counter = AtomicInteger()
    }
}
