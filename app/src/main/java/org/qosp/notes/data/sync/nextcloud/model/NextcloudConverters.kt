package org.qosp.notes.data.sync.nextcloud.model

import org.qosp.notes.data.model.Note
import org.qosp.notes.data.sync.nextcloud.NextcloudAttachment
import org.qosp.notes.data.sync.nextcloud.NextcloudNote

/**
 * @param attachments the note's images as the server knows them, or null to
 * leave whatever it already holds untouched — see [NextcloudNote.attachments].
 */
fun Note.asNextcloudNote(
    id: Long,
    category: String,
    attachments: List<NextcloudAttachment>? = null,
): NextcloudNote = NextcloudNote(
    id = id,
    title = title,
    content = toStorableContent(),
    category = category,
    favorite = isPinned,
    modified = modifiedDate,
    attachments = attachments,
)
