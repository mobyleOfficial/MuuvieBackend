package org.mobyle.data.local.feed

import io.mockk.mockk
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.Database
import org.mobyle.data.local.database.DatabaseMigrations
import org.mobyle.data.local.shelves.ShelfDataSourceImpl
import org.mobyle.data.local.user.UserDatabaseDataSourceImpl
import org.mobyle.data.local.watch.WatchDataSourceImpl
import org.mobyle.domain.model.ActivityDraft
import org.mobyle.domain.model.DeviceToken
import org.mobyle.domain.model.FeedCursor
import org.mobyle.domain.model.NotificationPrefs
import org.mobyle.domain.model.ShelfDraft
import org.mobyle.domain.model.SystemMessage
import org.mobyle.domain.model.User
import org.mobyle.domain.model.WatchLogDraft
import org.mobyle.domain.model.vocabulary.ActivitySubject
import org.mobyle.domain.model.vocabulary.ActivityType
import org.mobyle.domain.model.vocabulary.Emotion
import org.mobyle.domain.model.vocabulary.FeedScope
import org.mobyle.domain.model.vocabulary.MovieActivityKind
import org.mobyle.domain.model.vocabulary.Rating
import org.mobyle.domain.model.vocabulary.Visibility
import org.mobyle.domain.model.vocabulary.WatchMode
import org.mobyle.domain.repository.WatchableMovie
import org.mobyle.domain.usecase.feed.ActivityPublisher
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class SocialDataSourceImplTest {

    private lateinit var dataSource: DataSource
    private val activities = ActivityDataSourceImpl()
    private val follows = FollowDataSourceImpl()
    private val conversations = ConversationDataSourceImpl()
    private val notifications = NotificationDataSourceImpl()
    private val watch = WatchDataSourceImpl()
    private val shelves = ShelfDataSourceImpl()
    private val users = UserDatabaseDataSourceImpl()
    private val movie = WatchableMovie(10, 100, "Halloween", "/h.jpg", 91)

    @BeforeTest
    fun setUp() {
        val name = "social_${counter.incrementAndGet()}"
        postgres.postgresDatabase.connection.use { it.createStatement().execute("CREATE DATABASE $name") }
        dataSource = postgres.getDatabase("postgres", name)
        DatabaseMigrations.migrate(dataSource)
        Database.connect(dataSource)

        listOf("ana", "bia", "caio").forEach {
            users.save(User(id = it, email = "$it@x.com", username = it, avatar = null, bio = null, createdAt = ""))
        }
        execute("INSERT INTO movies (id, tmdb_id, title, poster_path, runtime) VALUES (10, 100, 'Halloween', '/h.jpg', 91);")
    }

    // ── Feed visibility (F6.4) ──────────────────────────────────────────────

    @Test
    fun `a follower who is not followed back only sees public activities`() {
        follows.follow("bia", "ana")
        publish("ana", Visibility.PUBLIC)
        publish("ana", Visibility.MUTUAL)
        publish("ana", Visibility.SECRET)

        val seen = activities.feed("bia", FeedScope.FOLLOWING, null, 20).results

        assertEquals(listOf(Visibility.PUBLIC), seen.map { it.visibility })
        assertFalse(seen.single().mutual)
    }

    @Test
    fun `mutuals also see mutual activities, never secret ones`() {
        follows.follow("bia", "ana")
        follows.follow("ana", "bia")
        publish("ana", Visibility.PUBLIC)
        publish("ana", Visibility.MUTUAL)
        publish("ana", Visibility.SECRET)

        val seen = activities.feed("bia", FeedScope.FOLLOWING, null, 20).results

        assertEquals(setOf(Visibility.PUBLIC, Visibility.MUTUAL), seen.map { it.visibility }.toSet())
        assertTrue(seen.all { it.mutual })
    }

    @Test
    fun `the feed mixes me and the people I follow, never strangers`() {
        follows.follow("bia", "ana")
        publish("ana", Visibility.PUBLIC)
        publish("bia", Visibility.SECRET)
        publish("caio", Visibility.PUBLIC)

        val all = activities.feed("bia", FeedScope.ALL, null, 20).results
        val me = activities.feed("bia", FeedScope.ME, null, 20).results

        assertEquals(setOf("ana", "bia"), all.map { it.actor.id }.toSet())
        assertEquals(listOf("bia"), me.map { it.actor.id })
        assertTrue(me.single().self)
    }

    @Test
    fun `a hidden activity leaves every feed but the author's own list`() {
        follows.follow("bia", "ana")
        val id = publish("ana", Visibility.PUBLIC)

        activities.setHidden(id, true)

        assertTrue(activities.feed("bia", FeedScope.ALL, null, 20).results.isEmpty())
        assertTrue(activities.feed("ana", FeedScope.ALL, null, 20).results.isEmpty())
        assertTrue(activities.feed("ana", FeedScope.ME, null, 20).results.single().hidden)
    }

    @Test
    fun `the cursor stays stable when activities arrive between pages`() {
        val ids = (1..5).map { publish("ana", Visibility.PUBLIC) }

        val first = activities.feed("ana", FeedScope.ME, null, 2)
        publish("ana", Visibility.PUBLIC) // newer than everything: must not shift the next pages
        val second = activities.feed("ana", FeedScope.ME, FeedCursor.decode(first.nextCursor), 2)
        val third = activities.feed("ana", FeedScope.ME, FeedCursor.decode(second.nextCursor), 2)

        assertEquals(ids.reversed(), (first.results + second.results + third.results).map { it.id })
        assertNull(third.nextCursor)
    }

    @Test
    fun `loves are counted once per user`() {
        follows.follow("bia", "ana")
        val id = publish("ana", Visibility.PUBLIC)

        activities.love(id, "bia")
        val loves = activities.love(id, "bia")
        val asSeen = activities.feed("bia", FeedScope.ALL, null, 20).results.single()

        assertEquals(1, loves.loves)
        assertTrue(asSeen.loved)
        assertEquals(0, activities.unlove(id, "bia").loves)
    }

    @Test
    fun `movie activities only come from people I follow, or only from me`() {
        follows.follow("bia", "ana")
        publish("ana", Visibility.PUBLIC, movieId = 10)
        publish("caio", Visibility.PUBLIC, movieId = 10)
        publish("bia", Visibility.SECRET, movieId = 10)

        val seen = activities.movieActivities("bia", 10, FeedScope.FOLLOWING, null, null, 20).results
        val mine = activities.movieActivities("bia", 10, FeedScope.ME, null, null, 20).results

        assertEquals(listOf("ana"), seen.map { it.actor.id })
        assertEquals(listOf("bia"), mine.map { it.actor.id })
        assertTrue(activities.movieActivities(null, 10, FeedScope.ME, null, null, 20).results.isEmpty())
    }

    @Test
    fun `movie activities split into ratings and updates`() {
        val publisher = ActivityPublisher(activities)
        publisher.sessionLogged("ana", movie, watch.createLog("ana", 10, logDraft(completed = false)))
        publisher.movieRated("bia", movie, watch.setRating("bia", 10, Rating.LIKED, "Bom", false))
        publisher.sessionLogged("caio", movie, watch.createLog("caio", 10, logDraft(completed = true)))
        publisher.movieRated("caio", movie, watch.setRating("caio", 10, Rating.LOVED, null, false))

        fun actors(kind: MovieActivityKind?) =
            activities.movieActivities(null, 10, FeedScope.ALL, kind, null, 20).results.map { it.actor.id }.toSet()

        // caio's session and rating share one activity, so it is both.
        assertEquals(setOf("bia", "caio"), actors(MovieActivityKind.RATINGS))
        assertEquals(setOf("ana", "caio"), actors(MovieActivityKind.UPDATES))
        assertEquals(setOf("ana", "bia", "caio"), actors(null))
    }

    @Test
    fun `the Registros tab shows public activities from anyone, mutual ones only to mutuals`() {
        follows.follow("bia", "ana")
        follows.follow("ana", "bia")
        publish("ana", Visibility.MUTUAL, movieId = 10)
        publish("caio", Visibility.PUBLIC, movieId = 10)
        publish("caio", Visibility.MUTUAL, movieId = 10)
        publish("caio", Visibility.SECRET, movieId = 10)
        publish("bia", Visibility.SECRET, movieId = 10)

        val asBia = activities.movieActivities("bia", 10, FeedScope.ALL, null, null, 20).results
        val loggedOut = activities.movieActivities(null, 10, FeedScope.ALL, null, null, 20).results

        assertEquals(
            setOf("ana" to Visibility.MUTUAL, "caio" to Visibility.PUBLIC, "bia" to Visibility.SECRET),
            asBia.map { it.actor.id to it.visibility }.toSet()
        )
        assertEquals(listOf("caio" to Visibility.PUBLIC), loggedOut.map { it.actor.id to it.visibility })
    }

    // ── Publisher: merging and retracting (B6 dedupe) ───────────────────────

    @Test
    fun `a session and a rating of the same movie in the window become one activity`() {
        val publisher = ActivityPublisher(activities)
        val log = watch.createLog("ana", 10, logDraft(completed = true))

        publisher.sessionLogged("ana", movie, log)
        publisher.movieRated("ana", movie, watch.setRating("ana", 10, Rating.LOVED, "Clássico", false))

        val activity = activities.feed("ana", FeedScope.ME, null, 20).results.single()
        assertEquals(ActivityType.FINISHED_MOVIE, activity.type)
        assertEquals("Halloween", activity.payload["movie"]!!.jsonObject["title"]!!.jsonPrimitive.content)
        assertEquals("loved", activity.payload["rating"]!!.jsonObject["rating"]!!.jsonPrimitive.content)
        assertEquals("joy", activity.payload["log"]!!.jsonObject["emotions"].toString().trim('[', ']', '"'))
    }

    @Test
    fun `out of the window a new session is a new activity`() {
        val later = object : Clock {
            override fun now(): Instant = Clock.System.now() + 11.minutes
        }
        ActivityPublisher(activities).sessionLogged("ana", movie, watch.createLog("ana", 10, logDraft(completed = false)))

        ActivityPublisher(activities, later).sessionLogged("ana", movie, watch.createLog("ana", 10, logDraft(completed = true)))

        assertEquals(
            listOf(ActivityType.FINISHED_MOVIE, ActivityType.WATCH_SESSION),
            activities.feed("ana", FeedScope.ME, null, 20).results.map { it.type }
        )
    }

    @Test
    fun `deleting the session keeps the rating, and removing it too deletes the activity`() {
        val publisher = ActivityPublisher(activities)
        val log = watch.createLog("ana", 10, logDraft(completed = true))
        publisher.sessionLogged("ana", movie, log)
        publisher.movieRated("ana", movie, watch.setRating("ana", 10, Rating.NEUTRAL, null, false))

        publisher.sessionDeleted("ana", 10, log.id)
        assertEquals(ActivityType.RATING, activities.feed("ana", FeedScope.ME, null, 20).results.single().type)

        publisher.ratingRemoved("ana", 10)
        assertTrue(activities.feed("ana", FeedScope.ME, null, 20).results.isEmpty())
    }

    @Test
    fun `a merged activity is as private as its most private part`() {
        val publisher = ActivityPublisher(activities)
        publisher.sessionLogged("ana", movie, watch.createLog("ana", 10, logDraft(completed = true, visibility = Visibility.SECRET)))
        publisher.movieRated("ana", movie, watch.setRating("ana", 10, Rating.FAVORITE, null, false))

        assertEquals(Visibility.SECRET, activities.feed("ana", FeedScope.ME, null, 20).results.single().visibility)
    }

    @Test
    fun `changing a shelf updates its activity and deleting it removes the activity`() {
        val publisher = ActivityPublisher(activities)
        val shelf = shelves.createShelf("ana", ShelfDraft("Giallo", null, null, Visibility.PUBLIC))
        publisher.shelfCreated(shelf)

        publisher.shelfChanged(shelves.updateShelf(shelf.id, ShelfDraft("Giallo!", null, null, Visibility.SECRET)))
        val activity = activities.feed("ana", FeedScope.ME, null, 20).results.single()
        assertEquals(Visibility.SECRET, activity.visibility)
        assertEquals("Giallo!", activity.payload["shelf"]!!.jsonObject["name"]!!.jsonPrimitive.content)

        publisher.shelfDeleted("ana", shelf.id)
        assertTrue(activities.feed("ana", FeedScope.ME, null, 20).results.isEmpty())
    }

    // ── Follows ─────────────────────────────────────────────────────────────

    @Test
    fun `follow is idempotent and the lists carry the newest follow first`() {
        assertTrue(follows.follow("bia", "ana"))
        assertFalse(follows.follow("bia", "ana"))
        follows.follow("caio", "ana")

        val followers = follows.followers("ana", org.mobyle.domain.model.PageRequest(1, 20))
        assertEquals(listOf("caio", "bia"), followers.results.map { it.user.id })
        assertTrue(follows.status("ana", "bia").followedBy)
        assertFalse(follows.status("ana", "bia").mutual)
        assertTrue(follows.unfollow("bia", "ana"))
        assertFalse(follows.unfollow("bia", "ana"))
    }

    // ── Sussurros ───────────────────────────────────────────────────────────

    @Test
    fun `a conversation counts unread messages until it is read`() {
        follows.follow("ana", "bia")
        follows.follow("bia", "ana")
        val id = conversations.getOrCreateConversation("ana", "bia")
        assertEquals(id, conversations.getOrCreateConversation("bia", "ana"))

        conversations.addMessage(id, "ana", "Viu Suspiria?")
        conversations.addMessage(id, "ana", "Tá no Mubi")

        assertEquals(2, conversations.unreadCount("bia").messages)
        assertEquals(0, conversations.unreadCount("ana").messages)
        val forBia = conversations.conversations("bia", null, 20).results.single()
        assertEquals("ana", forBia.other.id)
        assertTrue(forBia.enabled)
        assertEquals("Tá no Mubi", forBia.lastMessage!!.body)

        conversations.markRead(id, "bia")
        assertEquals(0, conversations.unreadCount("bia").conversations)
    }

    @Test
    fun `a conversation is disabled once they stop being mutuals`() {
        follows.follow("ana", "bia")
        follows.follow("bia", "ana")
        val id = conversations.getOrCreateConversation("ana", "bia")
        conversations.addMessage(id, "ana", "Oi")

        follows.unfollow("bia", "ana")
        conversations.addMessage(id, null, SystemMessage.MUTUAL_LOST)

        val conversation = conversations.conversation("ana", id)!!
        assertFalse(conversation.enabled)
        assertTrue(conversation.lastMessage!!.system)
        val messages = conversations.messages("ana", id, null, 1)
        assertEquals(SystemMessage.MUTUAL_LOST, messages.results.single().body)
        assertEquals("Oi", conversations.messages("ana", id, FeedCursor.decode(messages.nextCursor), 1).results.single().body)
    }

    // ── Push devices and preferences ────────────────────────────────────────

    @Test
    fun `a device token moves to the account that registers it last`() {
        notifications.saveDevice("ana", DeviceToken("android", "t1", "pt-BR"))
        notifications.saveDevice("bia", DeviceToken("android", "t1", "en"))

        assertTrue(notifications.devices("ana").isEmpty())
        assertEquals("en", notifications.devices("bia").single().locale)

        notifications.removeTokens(listOf("t1"))
        assertTrue(notifications.devices("bia").isEmpty())
    }

    @Test
    fun `preferences default to on and keep what is saved`() {
        assertTrue(notifications.prefs("ana").messages)

        notifications.savePrefs("ana", NotificationPrefs(messages = false))

        assertFalse(notifications.prefs("ana").messages)
        assertTrue(notifications.prefs("ana").loves)
    }

    private fun publish(actor: String, visibility: Visibility, movieId: Long? = null): Long = activities.insert(
        ActivityDraft(
            actor, ActivityType.NEW_SHELF, visibility, ActivitySubject.SHELF, 1, movieId,
            buildJsonObject { put("shelf", buildJsonObject { put("name", "x") }) }
        )
    )

    private fun logDraft(completed: Boolean, visibility: Visibility = Visibility.PUBLIC) = WatchLogDraft(
        watchedOn = LocalDate(2026, 10, 1),
        mode = WatchMode.WATCHED,
        minutesWatched = null,
        percent = if (completed) 100 else null,
        completed = completed,
        venue = null,
        providerName = null,
        emotions = listOf(Emotion.JOY),
        gif = null,
        comment = "Que filme",
        hasSpoiler = false,
        visibility = visibility
    )

    private fun execute(sql: String) = dataSource.connection.use { it.createStatement().execute(sql) }

    companion object {
        private val postgres: EmbeddedPostgres by lazy { EmbeddedPostgres.start() }
        private val counter = AtomicInteger()
    }
}
