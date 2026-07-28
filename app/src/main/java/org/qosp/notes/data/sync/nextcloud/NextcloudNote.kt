package org.qosp.notes.data.sync.nextcloud

import kotlinx.serialization.Serializable
import org.qosp.notes.data.model.NoteColor

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
    /**
     * The note's colour the way the server spells it: the empty string for the
     * default, otherwise a [NoteColor] name.
     *
     * Null is again "not mentioned", and again it means "leave it alone" in
     * both directions — a stock Nextcloud has no such field, so a pull from one
     * must not reset the colour the phone holds.
     */
    val color: String? = null,
)

/** The empty string for [NoteColor.Default], so the phone stores what the web app stores. */
fun NoteColor.asNextcloudColor(): String = if (this == NoteColor.Default) "" else name

/**
 * Null when the server said nothing this build can use — the field was absent,
 * or it named a colour added after this version — so the caller keeps whatever
 * colour the note already has instead of dropping it to the default.
 */
fun String?.toNoteColorOrNull(): NoteColor? = when {
    this == null -> null
    isEmpty() -> NoteColor.Default
    else -> NoteColor.entries.firstOrNull { it.name.equals(this, ignoreCase = true) }
}
