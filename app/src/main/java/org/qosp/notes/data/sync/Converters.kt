package org.qosp.notes.data.sync

import org.qosp.notes.data.model.IdMapping
import org.qosp.notes.data.model.Note
import org.qosp.notes.data.sync.core.SyncAttachment
import org.qosp.notes.data.sync.core.SyncNote
import org.qosp.notes.data.sync.nextcloud.NextcloudNote
import org.qosp.notes.preferences.CloudService

fun NextcloudNote.asSyncNote() = SyncNote(
    id = id,
    idStr = id.toString(),
    content = content,
    title = title,
    lastModified = modified, // Nextcloud already uses epoch seconds.
    extra = etag,
    category = category,
    favorite = favorite,
    readOnly = readOnly == true,
    // A server that does not know about attachments omits the field, which is
    // indistinguishable from a note that has none — neither can take an image
    // away from the phone, since only an uploaded one is ever dropped locally.
    attachments = attachments.orEmpty().map {
        SyncAttachment(
            hash = it.hash,
            type = it.type,
            mime = it.mime,
            name = it.name,
            description = it.description,
        )
    },
)

// Convert SyncNote to local Note with full content
fun SyncNote.toLocalNote(defaultPinned: Boolean) = Note(
    id = 0L, // Will be assigned by a database
    title = title,
    content = content ?: "",
    isPinned = favorite ?: defaultPinned,
    modifiedDate = lastModified,
    notebookId = null, // TODO: Handle category to notebook conversion if needed
    isMarkdownEnabled = true // Default to Markdown enabled
)

fun SyncNote.updateLocalNote(localNote: Note) = localNote.copy(
    title = title,
    content = content ?: "",
    isPinned = favorite ?: localNote.isPinned,
    modifiedDate = lastModified,
)

fun SyncNote.getMapping(noteId: Long, service: CloudService) = IdMapping(
    localNoteId = noteId,
    remoteNoteId = id,
    provider = service,
    extras = extra,
    isDeletedLocally = false,
    storageUri = idStr
)
