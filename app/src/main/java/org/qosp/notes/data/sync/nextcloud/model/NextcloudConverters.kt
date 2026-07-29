package org.qosp.notes.data.sync.nextcloud.model

import org.qosp.notes.data.model.Note
import org.qosp.notes.data.sync.nextcloud.NextcloudAttachment
import org.qosp.notes.data.sync.nextcloud.NextcloudNote
import org.qosp.notes.data.sync.nextcloud.asNextcloudColor

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
    // Always sent, default included: the empty string is what clears a colour,
    // and omitting it would leave the server holding the old one forever.
    color = this.color.asNextcloudColor(),
    // Always sent, false included: the patch on the server only writes fields
    // the body names, so omitting it would leave an unarchive on the phone
    // invisible there.
    archived = isArchived,
)
