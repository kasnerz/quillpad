package org.qosp.notes.data.sync

import org.junit.Assert.assertEquals
import org.junit.Test
import org.qosp.notes.data.model.Note
import org.qosp.notes.data.model.NoteColor
import org.qosp.notes.data.sync.nextcloud.NextcloudNote
import org.qosp.notes.data.sync.nextcloud.model.asNextcloudNote

/**
 * The colour travels as a string in the note's `color` field — "" for the
 * default, otherwise a [NoteColor] name — which is what the web app writes.
 */
class ColorSyncTest {

    private fun remoteNote(color: String?) = NextcloudNote(
        id = 1L,
        content = "content",
        title = "title",
        category = "",
        favorite = false,
        modified = 100L,
        color = color,
    )

    @Test
    fun `a colour name off the server becomes that colour`() {
        assertEquals(NoteColor.Teal, remoteNote("Teal").asSyncNote().color)
    }

    @Test
    fun `an empty colour means the default`() {
        assertEquals(NoteColor.Default, remoteNote("").asSyncNote().color)
    }

    @Test
    fun `a server that never mentions the colour reports none`() {
        assertEquals(null, remoteNote(null).asSyncNote().color)
    }

    @Test
    fun `a colour this build does not know is treated as unsaid`() {
        assertEquals(null, remoteNote("Chartreuse").asSyncNote().color)
    }

    @Test
    fun `a remote colour wins when the remote note wins`() {
        val local = Note(id = 1L, color = NoteColor.Red)
        val merged = remoteNote("Blue").asSyncNote().updateLocalNote(local)
        assertEquals(NoteColor.Blue, merged.color)
    }

    @Test
    fun `clearing the colour remotely clears it locally`() {
        val local = Note(id = 1L, color = NoteColor.Red)
        val merged = remoteNote("").asSyncNote().updateLocalNote(local)
        assertEquals(NoteColor.Default, merged.color)
    }

    @Test
    fun `a backend without colours leaves the local one alone`() {
        val local = Note(id = 1L, color = NoteColor.Red)
        val merged = remoteNote(null).asSyncNote().updateLocalNote(local)
        assertEquals(NoteColor.Red, merged.color)
    }

    @Test
    fun `a note pulled for the first time keeps the colour it arrived with`() {
        assertEquals(NoteColor.Pink, remoteNote("Pink").asSyncNote().toLocalNote(defaultPinned = false).color)
    }

    @Test
    fun `a push names the colour`() {
        val note = Note(id = 1L, color = NoteColor.Orange)
        assertEquals("Orange", note.asNextcloudNote(id = 1L, category = "").color)
    }

    @Test
    fun `a push spells the default as the empty string, so it can clear one`() {
        val note = Note(id = 1L, color = NoteColor.Default)
        assertEquals("", note.asNextcloudNote(id = 1L, category = "").color)
    }
}
