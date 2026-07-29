package org.qosp.notes.data.sync

import org.junit.Assert.assertEquals
import org.junit.Test
import org.qosp.notes.data.model.Note
import org.qosp.notes.data.sync.nextcloud.NextcloudNote
import org.qosp.notes.data.sync.nextcloud.model.asNextcloudNote

/**
 * The archive travels as the note's boolean `archived` field, the same column
 * the web app toggles. A backend that has no archive says nothing, and silence
 * must never un-archive what the phone holds.
 */
class ArchiveSyncTest {

    private fun remoteNote(archived: Boolean?) = NextcloudNote(
        id = 1L,
        content = "content",
        title = "title",
        category = "",
        favorite = false,
        modified = 100L,
        archived = archived,
    )

    @Test
    fun `an archived note off the server reports archived`() {
        assertEquals(true, remoteNote(true).asSyncNote().archived)
    }

    @Test
    fun `a server that never mentions the archive reports none`() {
        assertEquals(null, remoteNote(null).asSyncNote().archived)
    }

    @Test
    fun `archiving on the web archives locally`() {
        val local = Note(id = 1L, isArchived = false)
        assertEquals(true, remoteNote(true).asSyncNote().updateLocalNote(local).isArchived)
    }

    @Test
    fun `unarchiving on the web unarchives locally`() {
        val local = Note(id = 1L, isArchived = true)
        assertEquals(false, remoteNote(false).asSyncNote().updateLocalNote(local).isArchived)
    }

    @Test
    fun `a backend without an archive leaves the local flag alone`() {
        val local = Note(id = 1L, isArchived = true)
        assertEquals(true, remoteNote(null).asSyncNote().updateLocalNote(local).isArchived)
    }

    @Test
    fun `a note pulled for the first time arrives in the archive it came from`() {
        assertEquals(true, remoteNote(true).asSyncNote().toLocalNote(defaultPinned = false).isArchived)
    }

    @Test
    fun `a note pulled from a backend without an archive is not archived`() {
        assertEquals(false, remoteNote(null).asSyncNote().toLocalNote(defaultPinned = false).isArchived)
    }

    @Test
    fun `a push names the archive flag`() {
        val note = Note(id = 1L, isArchived = true)
        assertEquals(true, note.asNextcloudNote(id = 1L, category = "").archived)
    }

    @Test
    fun `a push names it when false too, so it can unarchive`() {
        val note = Note(id = 1L, isArchived = false)
        assertEquals(false, note.asNextcloudNote(id = 1L, category = "").archived)
    }
}
