package org.qosp.notes.data.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable

@Serializable
@Parcelize
data class Attachment(
    val type: Type = Type.IMAGE,
    val path: String = "",
    val description: String = "",
    val fileName: String = "",
    /**
     * sha256 of the copy held by the sync server, or "" when this attachment has
     * never been uploaded. Cached here so that pushing a note does not have to
     * re-encode and re-hash every image each time, and so a pulled image can be
     * matched to the blob it came from without downloading it again.
     *
     * Local state only: it is never read from a backup, and an attachment that
     * predates it simply looks like one that has not been uploaded yet.
     */
    val syncHash: String = "",
) : Parcelable {
    enum class Type { AUDIO, IMAGE, VIDEO, GENERIC }

    fun isEmpty() = path.isEmpty() && description.isEmpty() && fileName.isEmpty()
}
