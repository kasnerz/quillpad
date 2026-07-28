package org.qosp.notes.data.sync

import org.qosp.notes.data.model.IdMapping
import org.qosp.notes.data.model.Note
import org.qosp.notes.data.model.NoteColor
import org.qosp.notes.data.sync.core.SyncAttachment
import org.qosp.notes.data.sync.core.SyncNote
import org.qosp.notes.data.sync.nextcloud.NextcloudNote
import org.qosp.notes.data.sync.nextcloud.toNoteColorOrNull
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
    color = color.toNoteColorOrNull(),
)

// A checklist line in the format taskListToMd() writes and mdToTaskList()
// reads. The Notes API has no field for list-ness, so a task list travels as
// markdown and arrives looking like any other note — the same rule the server
// applies in inferNoteType() is what turns it back into a list here.
private val checklistLine = Regex("""^\s*[-+*] *\[[ xX]] ?.*$""")

private fun isChecklistMarkdown(content: String): Boolean {
    var found = false
    for (line in content.lines()) {
        if (line.isBlank()) continue
        if (!checklistLine.matches(line)) return false
        found = true
    }
    return found
}

/**
 * Decides list-ness from the content that came off the server, so a note whose
 * every line is a checkbox becomes a real task list rather than a text note
 * rendered as markdown — and one that stopped being a checklist elsewhere stops
 * being a list here.
 */
fun Note.withListStateFromContent(): Note = when {
    isChecklistMarkdown(content) -> copy(isList = true, taskList = mdToTaskList(content), content = "")
    isList -> copy(isList = false, taskList = listOf())
    else -> this
}

// Convert SyncNote to local Note with full content
fun SyncNote.toLocalNote(defaultPinned: Boolean) = Note(
    id = 0L, // Will be assigned by a database
    title = title,
    content = content ?: "",
    isPinned = favorite ?: defaultPinned,
    modifiedDate = lastModified,
    notebookId = null, // TODO: Handle category to notebook conversion if needed
    isMarkdownEnabled = true, // Default to Markdown enabled
    color = color ?: NoteColor.Default,
).withListStateFromContent()

fun SyncNote.updateLocalNote(localNote: Note) = localNote.copy(
    title = title,
    content = content ?: "",
    isPinned = favorite ?: localNote.isPinned,
    modifiedDate = lastModified,
    color = color ?: localNote.color,
)

fun SyncNote.getMapping(noteId: Long, service: CloudService) = IdMapping(
    localNoteId = noteId,
    remoteNoteId = id,
    provider = service,
    extras = extra,
    isDeletedLocally = false,
    storageUri = idStr
)
