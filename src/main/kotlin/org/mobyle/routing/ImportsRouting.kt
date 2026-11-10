package org.mobyle.routing

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveMultipart
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mobyle.domain.model.ImportFile
import org.mobyle.domain.model.PayloadTooLargeException
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import org.mobyle.data.remote.auth.authenticateJWT
import org.mobyle.di.injection
import org.mobyle.domain.usecase.auth.ValidateToken
import org.mobyle.domain.usecase.imports.CancelImport
import org.mobyle.domain.usecase.imports.ConfirmImport
import org.mobyle.domain.usecase.imports.ConfirmImportInput
import org.mobyle.domain.usecase.imports.GetImport
import org.mobyle.domain.usecase.imports.GetImports
import org.mobyle.domain.usecase.imports.GetUnmatchedItems
import org.mobyle.domain.usecase.imports.ResolveUnmatchedItem
import org.mobyle.domain.usecase.imports.ResolveUnmatchedInput
import org.mobyle.domain.usecase.imports.StartFileImport
import org.mobyle.domain.usecase.imports.StartImport
import org.mobyle.domain.usecase.imports.StartImportInput

/** Import jobs (phase 3, B3). Only mounted under /api/v1; every route is the owner's. */
fun Route.getImportsRouting() {
    val validateToken by injection<ValidateToken>()
    val startImport by injection<StartImport>()
    val startFileImport by injection<StartFileImport>()
    val getImport by injection<GetImport>()
    val getImports by injection<GetImports>()
    val confirmImport by injection<ConfirmImport>()
    val cancelImport by injection<CancelImport>()
    val getUnmatchedItems by injection<GetUnmatchedItems>()
    val resolveUnmatchedItem by injection<ResolveUnmatchedItem>()

    // JSON starts the Filmow login import; multipart/form-data uploads files (csv-import spec).
    post("/imports") {
        val principal = call.authenticateJWT(validateToken) ?: return@post
        if (call.request.contentType().match(ContentType.MultiPart.FormData)) {
            val upload = call.receiveImportUpload()
            call.respond(HttpStatusCode.Accepted, startFileImport(principal.claims.userId, upload.source, upload.files))
            return@post
        }
        val input = call.receive<StartImportInput>()
        call.respond(HttpStatusCode.Accepted, startImport(principal.claims.userId, input))
    }

    get("/imports") {
        val principal = call.authenticateJWT(validateToken) ?: return@get
        val page = call.pageRequest() ?: return@get
        call.respond(getImports(principal.claims.userId, page))
    }

    get("/imports/{id}") {
        val principal = call.authenticateJWT(validateToken) ?: return@get
        val jobId = call.longParameter("id") ?: return@get
        call.respond(getImport(principal.claims.userId, jobId))
    }

    post("/imports/{id}/confirm") {
        val principal = call.authenticateJWT(validateToken) ?: return@post
        val jobId = call.longParameter("id") ?: return@post
        val input = call.receive<ConfirmImportInput>()
        call.respond(HttpStatusCode.Accepted, confirmImport(principal.claims.userId, jobId, input))
    }

    post("/imports/{id}/cancel") {
        val principal = call.authenticateJWT(validateToken) ?: return@post
        val jobId = call.longParameter("id") ?: return@post
        call.respond(cancelImport(principal.claims.userId, jobId))
    }

    get("/imports/{id}/unmatched") {
        val principal = call.authenticateJWT(validateToken) ?: return@get
        val jobId = call.longParameter("id") ?: return@get
        val page = call.pageRequest() ?: return@get
        call.respond(getUnmatchedItems(principal.claims.userId, jobId, page))
    }

    post("/imports/{id}/unmatched/{itemId}") {
        val principal = call.authenticateJWT(validateToken) ?: return@post
        val jobId = call.longParameter("id") ?: return@post
        val itemId = call.longParameter("itemId") ?: return@post
        val input = call.receive<ResolveUnmatchedInput>()
        call.respond(resolveUnmatchedItem(principal.claims.userId, jobId, itemId, input))
    }
}

/** Upload size cap of POST /imports, all files together. A Letterboxd export is a few hundred KB. */
private const val MAX_UPLOAD_BYTES = 10 * 1024 * 1024

private class ImportUpload(val source: String?, val files: List<ImportFile>)

/** Reads the `source` field and every `file` part, refusing the request past [MAX_UPLOAD_BYTES]. */
private suspend fun ApplicationCall.receiveImportUpload(): ImportUpload {
    var source: String? = null
    val files = mutableListOf<ImportFile>()
    var totalBytes = 0L
    receiveMultipart().forEachPart { part ->
        try {
            when {
                part is PartData.FormItem && part.name == "source" -> source = part.value.trim()
                part is PartData.FileItem && part.name == "file" -> {
                    val remaining = MAX_UPLOAD_BYTES - totalBytes
                    // One byte past the limit tells a file that is exactly at it from one over it.
                    val bytes = withContext(Dispatchers.IO) {
                        part.provider().toInputStream().use { stream -> stream.readNBytes((remaining + 1).toInt()) }
                    }
                    totalBytes += bytes.size
                    if (totalBytes > MAX_UPLOAD_BYTES) {
                        throw PayloadTooLargeException("file_too_large", "The files are larger than 10 MB")
                    }
                    files.add(ImportFile(part.originalFileName ?: "file", bytes))
                }
            }
        } finally {
            part.dispose()
        }
    }
    return ImportUpload(source, files)
}
