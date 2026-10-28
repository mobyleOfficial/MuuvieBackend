package org.mobyle.domain.model.vocabulary

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VocabularyTest {

    @Test
    fun testRatingOrderIsThePickerOrder() {
        assertEquals(
            listOf("favorite", "loved", "liked", "neutral", "not_for_me", "abandoned", "on_hold"),
            Rating.entries.map { it.wireValue }
        )
    }

    @Test
    fun testOnlyVerdictsOfferTheFullSession() {
        Rating.entries.filter { it != Rating.ABANDONED && it != Rating.ON_HOLD }.forEach { assertTrue(it.isVerdict, "$it") }
        assertFalse(Rating.ABANDONED.isVerdict)
        assertFalse(Rating.ON_HOLD.isVerdict)
    }

    @Test
    fun testImportedStarsBecomeLabels() {
        val expected = mapOf(
            0.5 to Rating.NOT_FOR_ME,
            2.0 to Rating.NOT_FOR_ME,
            2.5 to Rating.NEUTRAL,
            3.0 to Rating.NEUTRAL,
            3.5 to Rating.LIKED,
            4.0 to Rating.LIKED,
            4.5 to Rating.LOVED,
            5.0 to Rating.LOVED,
            // The old 0.5-10 scale is halved first.
            7.0 to Rating.LIKED,
            10.0 to Rating.LOVED
        )

        expected.forEach { (stars, rating) -> assertEquals(rating, Rating.fromStars(stars), "stars=$stars") }
    }

    @Test
    fun testEmotionIdsAreStableAndUnique() {
        assertEquals(44, Emotion.entries.size)
        assertEquals((0 until 44).toList(), Emotion.entries.map { it.id })
        assertEquals(Emotion.CURIOSITY, Emotion.fromId(0))
        assertEquals(Emotion.APATHY, Emotion.fromId(43))
        assertNull(Emotion.fromId(44))
    }

    @Test
    fun testWireValuesMatchSerialNames() {
        assertEquals("\"mutual\"", Json.encodeToString(Visibility.MUTUAL))
        assertEquals("\"want_to_watch\"", Json.encodeToString(WatchStatus.WANT_TO_WATCH))
        assertEquals("\"not_for_me\"", Json.encodeToString(Rating.NOT_FOR_ME))
        assertEquals("\"on_hold\"", Json.encodeToString(Rating.ON_HOLD))
        assertEquals("\"physical_media\"", Json.encodeToString(WatchVenue.PHYSICAL_MEDIA))
        assertEquals("\"freak_out\"", Json.encodeToString(Emotion.FREAK_OUT))
        assertEquals("\"watchlist\"", Json.encodeToString(SystemShelfType.WATCHLIST))
        assertEquals("\"release_year\"", Json.encodeToString(ShelfSort.RELEASE_YEAR))
        assertEquals("\"percent\"", Json.encodeToString(WatchMode.PERCENT))

        Visibility.entries.forEach { assertEquals("\"${it.wireValue}\"", Json.encodeToString(it)) }
        WatchStatus.entries.forEach { assertEquals("\"${it.wireValue}\"", Json.encodeToString(it)) }
        Rating.entries.forEach { assertEquals("\"${it.wireValue}\"", Json.encodeToString(it)) }
        WatchVenue.entries.forEach { assertEquals("\"${it.wireValue}\"", Json.encodeToString(it)) }
        Emotion.entries.forEach { assertEquals("\"${it.wireValue}\"", Json.encodeToString(it)) }
        SystemShelfType.entries.forEach { assertEquals("\"${it.wireValue}\"", Json.encodeToString(it)) }
        ShelfSort.entries.forEach { assertEquals("\"${it.wireValue}\"", Json.encodeToString(it)) }
        WatchMode.entries.forEach { assertEquals("\"${it.wireValue}\"", Json.encodeToString(it)) }
    }
}
