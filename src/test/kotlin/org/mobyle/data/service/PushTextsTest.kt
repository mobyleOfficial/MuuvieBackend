package org.mobyle.data.service

import org.mobyle.domain.model.SocialEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Texts of the maratona and import pushes (B15.3). */
class PushTextsTest {

    @Test
    fun `a maratona push has its title and a text in the language of the device`() {
        val started = SocialEvent.MaratonaStarted("outubro", "Terror 70")

        assertEquals("Terror 70" to "A maratona começou. Hora do play!", PushTexts.of(started, "pt-BR"))
        assertEquals("Terror 70" to "The maratona started. Time to press play!", PushTexts.of(started, "en"))
        assertEquals(
            "Terror 70" to "La maratón terminó. Mira cómo te fue",
            PushTexts.of(SocialEvent.MaratonaFinished("outubro", "Terror 70"), "es")
        )
    }

    @Test
    fun `an import push tells the source, the status and how many watched movies`() {
        fun event(status: String) = SocialEvent.ImportUpdated(7, "filmow", status, 42)

        assertEquals(
            "Sua importação do Filmow está pronta" to "Encontramos 42 filmes assistidos. Revise e confirme",
            PushTexts.of(event("preview"), null)
        )
        assertEquals("Filmow import finished" to "42 movies are now in Watched", PushTexts.of(event("done"), "en"))
        assertEquals(
            "La importación de Filmow falló" to "Abre la app para intentarlo de nuevo",
            PushTexts.of(event("failed"), "es")
        )
        assertNull(PushTexts.of(event("importing"), "pt"))
    }
}
