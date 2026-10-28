package org.mobyle.domain.repository

import org.mobyle.domain.model.SocialEvent

/** Delivers social events to a user, in real time (WebSocket) and by push when they allow it. */
interface SocialNotifier {
    fun notify(userId: String, event: SocialEvent)
}
