package org.mobyle.data.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.mobyle.data.remote.push.PushMessage
import org.mobyle.data.remote.push.PushSender
import org.mobyle.domain.model.NotificationPrefs
import org.mobyle.domain.model.SocialEvent
import org.mobyle.domain.model.nameToShow
import org.mobyle.domain.repository.NotificationRepository
import org.mobyle.domain.repository.SocialNotifier
import org.slf4j.LoggerFactory

/**
 * Delivers social events off the request thread: always on the user's open WebSockets, in the
 * order they happened (a message before the badge it changes), and by push to their devices
 * when the event has a notification and their preferences allow it.
 */
class SocialNotifierImpl(
    private val webSocketManager: WebSocketManager,
    private val notifications: NotificationRepository,
    private val pushSender: PushSender
) : SocialNotifier {
    private val log = LoggerFactory.getLogger(SocialNotifierImpl::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val outbox = Channel<Pair<String, SocialEvent>>(Channel.UNLIMITED)

    init {
        scope.launch {
            for ((userId, event) in outbox) {
                try {
                    webSocketManager.send(userId, WsMessage(event.type, event.data))
                } catch (e: Exception) {
                    log.error("[SOCIAL] Could not deliver ${event.type} to $userId", e)
                }
                scope.launch {
                    try {
                        push(userId, event)
                    } catch (e: Exception) {
                        log.error("[SOCIAL] Could not push ${event.type} to $userId", e)
                    }
                }
            }
        }
    }

    override fun notify(userId: String, event: SocialEvent) {
        outbox.trySend(userId to event)
    }

    private suspend fun push(userId: String, event: SocialEvent) {
        if (!allowed(notifications.prefs(userId), event)) return
        val messages = notifications.devices(userId).mapNotNull { device ->
            PushTexts.of(event, device.locale)?.let { (title, body) -> PushMessage(device.token, title, body, event.data + ("type" to event.type)) }
        }
        val invalid = pushSender.send(messages)
        notifications.removeTokens(invalid)
    }

    private fun allowed(prefs: NotificationPrefs, event: SocialEvent): Boolean = when (event) {
        is SocialEvent.MessageReceived -> prefs.messages && !event.message.system
        is SocialEvent.NewFollower -> prefs.newFollowers
        is SocialEvent.ActivityLoved -> prefs.loves
        is SocialEvent.CineClubeDebate -> prefs.cineclubes
        is SocialEvent.MessageSent, is SocialEvent.UnreadChanged, is SocialEvent.WatchPartyChanged -> false
    }
}

/** Texts of the push notifications in the languages of the app; Portuguese is the default. */
internal object PushTexts {
    private const val BODY_MAX = 180

    fun of(event: SocialEvent, locale: String?): Pair<String, String>? {
        val language = locale?.take(2)?.lowercase()
        return when (event) {
            is SocialEvent.NewFollower -> event.follower.nameToShow to when (language) {
                "en" -> "started following you"
                "es" -> "empezó a seguirte"
                else -> "começou a seguir você"
            }
            is SocialEvent.ActivityLoved -> event.by.nameToShow to when (language) {
                "en" -> "loved your activity"
                "es" -> "amó tu actividad"
                else -> "amou sua atividade"
            }
            is SocialEvent.MessageReceived -> event.sender?.let { it.nameToShow to event.message.body.take(BODY_MAX) }
            is SocialEvent.CineClubeDebate -> event.title to when (language) {
                "en" -> "Today is debate day for ${event.movieTitle}"
                "es" -> "Hoy es el día del debate de ${event.movieTitle}"
                else -> "Hoje é dia de debate de ${event.movieTitle}"
            }.take(BODY_MAX)
            is SocialEvent.MessageSent, is SocialEvent.UnreadChanged, is SocialEvent.WatchPartyChanged -> null
        }
    }
}
