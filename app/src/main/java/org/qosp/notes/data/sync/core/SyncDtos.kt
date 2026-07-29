package org.qosp.notes.data.sync.core

import org.qosp.notes.data.model.Note
import org.qosp.notes.data.model.NoteColor

// Sealed class to represent remote operations
sealed class RemoteOperation {
    data class Create(val note: Note, val import: Boolean = false) : RemoteOperation()
    data class Update(val note: Note) : RemoteOperation()
    data class Delete(val note: Note) : RemoteOperation()
}

enum class SyncMethod {
    MAPPING,
    TITLE,
}

data class SyncNote(
    val id: Long,
    val idStr: String,
    val content: String?,
    val title: String,
    val lastModified: Long, // Epoch seconds
    val extra: String? = null,
    val category: String = "",
    val favorite: Boolean? = null,
    val readOnly: Boolean = false,
    val attachments: List<SyncAttachment> = listOf(),
    /** Null when the backend has no colour to report; the local one then stands. */
    val color: NoteColor? = null,
    /** Null when the backend has no archive of its own; the local flag then stands. */
    val archived: Boolean? = null,
)

/**
 * A file attached to a remote note, as a reference rather than the bytes: the
 * hash addresses it in the backend's store. Turning one of these into a local
 * [org.qosp.notes.data.model.Attachment] means downloading it, which is the
 * backend's job — see [ISyncBackend.resolveAttachments].
 */
data class SyncAttachment(
    val hash: String,
    val type: String = "IMAGE",
    val mime: String = "",
    val name: String = "",
    val description: String = "",
)
