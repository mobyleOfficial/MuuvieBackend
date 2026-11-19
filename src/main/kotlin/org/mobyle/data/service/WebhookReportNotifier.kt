package org.mobyle.data.service

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.mobyle.domain.model.ReportDraft
import org.mobyle.domain.repository.ReportNotifier
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("WebhookReportNotifier")

/**
 * Posts each report to a chat webhook (REPORTS_WEBHOOK_URL) so the team sees it within the 24 hours
 * the App Store expects. The body carries both `text` (Slack) and `content` (Discord).
 */
class WebhookReportNotifier(
    private val httpClient: HttpClient,
    private val url: String,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : ReportNotifier {

    override fun reported(id: Long, draft: ReportDraft) {
        val text = "Muuvie report #$id: ${draft.target.wireValue} ${draft.targetId} (${draft.reason.wireValue}) " +
            "against user ${draft.reportedUserId ?: "?"} by ${draft.reporterId}" +
            (draft.details?.let { "\n> ${it.take(500)}" } ?: "")
        scope.launch {
            runCatching {
                httpClient.post(url) {
                    contentType(ContentType.Application.Json)
                    setBody(buildJsonObject { put("text", text); put("content", text) }.toString())
                }
            }.onFailure { log.warn("Could not post report $id to the webhook: ${it.message}") }
        }
    }
}
