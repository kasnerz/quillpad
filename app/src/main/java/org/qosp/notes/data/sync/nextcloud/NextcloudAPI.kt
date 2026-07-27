package org.qosp.notes.data.sync.nextcloud

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.RequestBody
import okhttp3.ResponseBody
import org.qosp.notes.data.sync.nextcloud.model.NextcloudCapabilitiesResult
import org.qosp.notes.data.sync.nextcloud.model.NextcloudNotesCapabilities
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.HEAD
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Streaming
import retrofit2.http.Url

const val baseURL = "index.php/apps/notes/api/v1/"

/**
 * Attachment storage. Outside the Nextcloud Notes API, and only implemented by
 * the server this fork syncs against.
 */
const val attachmentsURL = "web/v1/attachments"

interface NextcloudAPI {
    @GET
    suspend fun getNotesAPI(
        @Url url: String,
        @Header("Authorization") auth: String,
    ): List<NextcloudNote>

    @GET
    suspend fun getNoteAPI(
        @Url url: String,
        @Header("Authorization") auth: String,
    ): NextcloudNote

    @POST
    suspend fun createNoteAPI(
        @Body note: NextcloudNote,
        @Url url: String,
        @Header("Authorization") auth: String,
    ): NextcloudNote

    @PUT
    suspend fun updateNoteAPI(
        @Body note: NextcloudNote,
        @Url url: String,
        @Header("If-Match") etag: String,
        @Header("Authorization") auth: String,
    ): NextcloudNote

    @DELETE
    suspend fun deleteNoteAPI(
        @Url url: String,
        @Header("Authorization") auth: String,
    )

    @Headers(
        "OCS-APIRequest: true",
        "Accept: application/json"
    )
    @GET
    suspend fun getAllCapabilitiesAPI(
        @Url url: String,
        @Header("Authorization") auth: String,
    ): NextcloudCapabilitiesResult

    /** The file itself is the whole body; its media type carries the mime. */
    @POST
    suspend fun uploadAttachmentAPI(
        @Url url: String,
        @Body body: RequestBody,
        @Header("Authorization") auth: String,
    ): NextcloudBlob

    /** Status only — 200 when the server already holds this content, 404 when not. */
    @HEAD
    suspend fun headAttachmentAPI(
        @Url url: String,
        @Header("Authorization") auth: String,
    ): Response<Unit>

    @Streaming
    @GET
    suspend fun downloadAttachmentAPI(
        @Url url: String,
        @Header("Authorization") auth: String,
    ): ResponseBody
}

private fun NextcloudConfig.attachmentUrl(hash: String) = remoteAddress + attachmentsURL + "/" + hash

/**
 * Whether the server already holds this content. Uploading is content-addressed
 * and idempotent, so this is only an optimisation — but it is the one that keeps
 * a routine sync from re-sending every photo on a note.
 */
suspend fun NextcloudAPI.hasAttachment(hash: String, config: NextcloudConfig): Boolean {
    return try {
        // Returning Response rather than the body means 404 — the ordinary
        // answer for something not stored yet — arrives as a status, not an
        // exception.
        headAttachmentAPI(url = config.attachmentUrl(hash), auth = config.credentials).isSuccessful
    } catch (e: Exception) {
        // Anything else is a server that cannot answer. Treating that as "not
        // stored" costs an upload attempt, which fails in turn and leaves the
        // note's existing attachments alone.
        Log.w("NextcloudAPI", "Could not check attachment $hash: ${e.message}")
        false
    }
}

suspend fun NextcloudAPI.uploadAttachment(body: RequestBody, config: NextcloudConfig): NextcloudBlob {
    return uploadAttachmentAPI(
        url = config.remoteAddress + attachmentsURL,
        body = body,
        auth = config.credentials,
    )
}

suspend fun NextcloudAPI.downloadAttachment(hash: String, config: NextcloudConfig): ResponseBody {
    return downloadAttachmentAPI(url = config.attachmentUrl(hash), auth = config.credentials)
}

suspend fun NextcloudAPI.getNotesCapabilities(config: NextcloudConfig): NextcloudNotesCapabilities? {
    val endpoint = "ocs/v2.php/cloud/capabilities"
    val fullUrl = config.remoteAddress + endpoint

    val response = withContext(Dispatchers.IO) {
        getAllCapabilitiesAPI(url = fullUrl, auth = config.credentials)
    }
    Log.d("NextcloudAPI", "getNotesCapabilities: $response")
    return response.ocs.data.capabilities.notes
}

suspend fun NextcloudAPI.deleteNote(noteId: Long, config: NextcloudConfig) {
    deleteNoteAPI(
        url = config.remoteAddress + baseURL + "notes/${noteId}",
        auth = config.credentials,
    )
}

suspend fun NextcloudAPI.updateNote(note: NextcloudNote, etag: String, config: NextcloudConfig): NextcloudNote {
    return updateNoteAPI(
        note = note,
        url = config.remoteAddress + baseURL + "notes/${note.id}",
        etag = "\"$etag\"",
        auth = config.credentials,
    )
}

suspend fun NextcloudAPI.createNote(note: NextcloudNote, config: NextcloudConfig): NextcloudNote {
    return createNoteAPI(
        note = note,
        url = config.remoteAddress + baseURL + "notes",
        auth = config.credentials,
    )
}

suspend fun NextcloudAPI.getNote(id: Long, config: NextcloudConfig): NextcloudNote {
    return getNoteAPI(
        url = config.remoteAddress + baseURL + "notes" + "/$id",
        auth = config.credentials
    )
}

suspend fun NextcloudAPI.getNotes(config: NextcloudConfig): List<NextcloudNote> {
    return getNotesAPI(
        url = config.remoteAddress + baseURL + "notes",
        auth = config.credentials,
    )
}

