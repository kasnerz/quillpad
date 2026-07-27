package org.qosp.notes.data.sync.nextcloud

import kotlinx.serialization.Serializable

@Serializable
data class NextcloudNote(
    val id: Long,
    val etag: String? = null,
    val content: String?,
    val title: String,
    val category: String,
    val favorite: Boolean,
    val modified: Long, // seconds
    val readOnly: Boolean? = null,
    val remoteId: String = id.toString(),
    /**
     * Null means "not mentioned": kotlinx.serialization omits a property that
     * still holds its default, and the server treats an absent field as "leave
     * the attachments alone". That distinction is load-bearing — a push whose
     * uploads failed sends null rather than an empty list, so a temporary
     * network failure cannot wipe the images off a note.
     */
    val attachments: List<NextcloudAttachment>? = null,
)
