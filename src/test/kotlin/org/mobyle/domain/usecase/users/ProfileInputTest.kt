package org.mobyle.domain.usecase.users

import org.mobyle.domain.model.ValidationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProfileInputTest {

    private fun soundtrack(url: String) =
        highlightDraftsOf(listOf(HighlightInput("soundtrack", true, emptyList(), null, null, url))).single().soundtrackUrl

    @Test
    fun `the soundtrack takes a Spotify or a YouTube Music link`() {
        assertEquals("https://open.spotify.com/track/1", soundtrack("https://open.spotify.com/track/1"))
        assertEquals(
            "https://music.youtube.com/playlist?list=PL1",
            soundtrack(" https://music.youtube.com/playlist?list=PL1 ")
        )
    }

    @Test
    fun `the soundtrack refuses other links`() {
        listOf("https://www.youtube.com/watch?v=1", "http://music.youtube.com/watch?v=1", "https://example.com/song").forEach {
            assertFailsWith<ValidationException> { soundtrack(it) }
        }
    }
}
