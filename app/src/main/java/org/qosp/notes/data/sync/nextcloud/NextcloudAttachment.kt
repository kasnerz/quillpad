package org.qosp.notes.data.sync.nextcloud

import kotlinx.serialization.Serializable

/**
 * One file attached to a note, as it travels on the wire. The bytes are not
 * here: they live in the server's blob store, addressed by the sha256 of their
 * content, and this is only the reference to them.
 *
 * Not part of the Nextcloud Notes API. A server that does not know the field
 * ignores it, and one that does leaves a note's attachments alone when a
 * request omits it.
 */
@Serializable
data class NextcloudAttachment(
    val hash: String,
    /** Matches [org.qosp.notes.data.model.Attachment.Type] by name. */
    val type: String = "IMAGE",
    val mime: String = "",
    val name: String = "",
    val description: String = "",
)

/** What the server replies with when a file has been stored. */
@Serializable
data class NextcloudBlob(
    val hash: String,
    val mime: String = "",
    val size: Long = 0,
)
