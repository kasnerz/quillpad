package org.qosp.notes.data.sync.nextcloud

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.qosp.notes.App
import org.qosp.notes.data.dao.NoteDao
import org.qosp.notes.data.model.Attachment
import org.qosp.notes.data.model.Note
import org.qosp.notes.data.sync.core.SyncAttachment
import org.qosp.notes.ui.attachments.getAttachmentUri
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * Moves attachment *bytes* between the phone and the server's blob store. The
 * note itself only ever carries references — the sha256 of each file — so this
 * is what turns those references into images on disk and back again.
 *
 * Images only. Audio, video and generic files stay on the phone: they are
 * routinely hundreds of megabytes, and nothing on the other side can display
 * them. They are carefully left alone rather than dropped, in both directions.
 */
class NextcloudAttachmentSync(
    private val context: Context,
    private val noteDao: NoteDao,
) {
    private val tag = javaClass.simpleName

    companion object {
        /** Longest edge of an uploaded image. A 12MP photo lands around 300–600 kB. */
        const val MAX_DIMENSION = 2048
        const val JPEG_QUALITY = 85

        /**
         * Below this an already-small image is uploaded byte for byte instead of
         * being re-encoded, so a screenshot or a diagram is not quietly turned
         * into a blurrier JPEG for no gain.
         */
        const val ORIGINAL_MAX_BYTES = 1 shl 20 // 1 MiB

        /** Matches maxBlobSize on the server; anything larger is refused there. */
        const val MAX_UPLOAD_BYTES = 32 shl 20 // 32 MiB
    }

    /* ------------------------------------------------------------------ *
     * Push                                                                *
     * ------------------------------------------------------------------ */

    /**
     * Uploads whatever of [note]'s images the server does not already hold and
     * returns the list to put on the note.
     *
     * Returns null when an image that has never been uploaded could not be, so
     * that the caller omits the field entirely: sending a list without it would
     * tell the server to forget images it may already have. A note whose images
     * all failed keeps its remote list untouched and is retried on the next sync.
     */
    suspend fun push(note: Note, api: NextcloudAPI, config: NextcloudConfig): List<NextcloudAttachment>? =
        withContext(Dispatchers.IO) {
            val images = note.attachments.filter { it.type == Attachment.Type.IMAGE }
            if (images.isEmpty()) return@withContext emptyList()

            val remote = mutableListOf<NextcloudAttachment>()
            // Local copies with their freshly learned hashes, keyed by path, so
            // the note can be updated afterwards without re-reading every file.
            val hashes = mutableMapOf<String, String>()

            for (image in images) {
                val hash = ensureUploaded(image, api, config)
                    // Already on the server from an earlier sync: keep pointing
                    // at it rather than dropping the image over a failed upload.
                    ?: image.syncHash.takeIf { it.isNotEmpty() }
                    ?: return@withContext null

                if (hash != image.syncHash) hashes[image.path] = hash
                remote.add(
                    NextcloudAttachment(
                        hash = hash,
                        type = image.type.name,
                        mime = mimeOf(image),
                        name = image.fileName.ifEmpty { image.description },
                        description = image.description,
                    )
                )
            }

            if (hashes.isNotEmpty()) rememberHashes(note.id, hashes)
            remote
        }

    /** Uploads if needed and answers with the content hash, or null on failure. */
    private suspend fun ensureUploaded(
        image: Attachment,
        api: NextcloudAPI,
        config: NextcloudConfig,
    ): String? = try {
        // The cheap path, and the common one: nothing to read, decode or send.
        if (image.syncHash.isNotEmpty() && api.hasAttachment(image.syncHash, config)) {
            image.syncHash
        } else {
            val uri = Uri.parse(image.path)
            val prepared = prepare(uri)
            when {
                prepared == null -> null
                prepared.bytes.size > MAX_UPLOAD_BYTES -> {
                    Log.w(tag, "Skipping ${prepared.bytes.size} byte attachment, over the size limit")
                    null
                }

                else -> {
                    val hash = sha256(prepared.bytes)
                    // Content-addressed, so this is idempotent — but skipping the
                    // body when the server already has it is the difference
                    // between a sync that costs nothing and one that re-sends
                    // every photo.
                    if (!api.hasAttachment(hash, config)) {
                        val body = prepared.bytes.toRequestBody(prepared.mime.toMediaTypeOrNull())
                        api.uploadAttachment(body, config).hash
                    } else {
                        hash
                    }
                }
            }
        }
    } catch (e: Exception) {
        Log.e(tag, "Failed to upload attachment ${image.path}: ${e.message}", e)
        null
    }

    /**
     * Writes the learned hashes back onto the note, so the next push can skip
     * straight to the cheap path.
     *
     * The note is re-read rather than reusing the copy that was pushed: an
     * upload takes seconds, and the user may well have carried on typing in the
     * meantime. Only the attachment list is touched, and `modifiedDate` is left
     * exactly as it was, so this cannot start a sync of its own.
     */
    private suspend fun rememberHashes(noteId: Long, hashes: Map<String, String>) = try {
        val current = noteDao.getById(noteId).first()
        if (current != null) {
            val updated = current.attachments.map { attachment ->
                hashes[attachment.path]
                    ?.let { attachment.copy(syncHash = it) }
                    ?: attachment
            }
            if (updated != current.attachments) noteDao.update(current.copy(attachments = updated).toEntity())
        }
    } catch (e: Exception) {
        // Losing the cache costs a re-upload next time, nothing more.
        Log.e(tag, "Could not store attachment hashes for note $noteId: ${e.message}", e)
    }

    /* ------------------------------------------------------------------ *
     * Pull                                                                *
     * ------------------------------------------------------------------ */

    /**
     * Brings [local] into line with the [remote] list, downloading any image the
     * phone does not have yet, and returns the attachments the note should end
     * up with.
     *
     * Two kinds of local attachment survive regardless of what the server says:
     * anything that is not an image, and any image that has never been uploaded
     * (`syncHash` empty). The latter matters — an image the server has never
     * been told about is missing from its list because it never knew, not
     * because someone deleted it, and dropping it would lose a photo that exists
     * nowhere else.
     */
    suspend fun pull(
        remote: List<SyncAttachment>,
        local: List<Attachment>,
        api: NextcloudAPI,
        config: NextcloudConfig,
    ): List<Attachment> = withContext(Dispatchers.IO) {
        val byHash = local.associateBy { it.syncHash }
        val resolved = mutableListOf<Attachment>()

        for (attachment in remote) {
            // A reference with no hash points at nothing, and would otherwise
            // match every local attachment that has never been uploaded.
            if (attachment.hash.isBlank()) continue

            val existing = byHash[attachment.hash]
            if (existing != null) {
                resolved.add(existing)
                continue
            }
            val downloaded = download(attachment, api, config)
            if (downloaded != null) resolved.add(downloaded)
            // A failed download is left out of this round and picked up on the
            // next sync, when the note is still newer on the server.
        }

        val keptLocally = local.filter { attachment ->
            attachment.type != Attachment.Type.IMAGE || attachment.syncHash.isEmpty()
        }
        resolved + keptLocally
    }

    private suspend fun download(
        attachment: SyncAttachment,
        api: NextcloudAPI,
        config: NextcloudConfig,
    ): Attachment? = try {
        val bytes = api.downloadAttachment(attachment.hash, config).use { it.bytes() }
        val fileName = "${attachment.hash}${extensionFor(attachment.mime)}"
        val directory = File(context.filesDir, App.MEDIA_FOLDER).also { it.mkdirs() }
        File(directory, fileName).writeBytes(bytes)

        val uri = getAttachmentUri(context, fileName)
        if (uri == null) {
            Log.e(tag, "No URI for downloaded attachment $fileName")
            null
        } else {
            Attachment(
                type = Attachment.Type.IMAGE,
                path = uri.toString(),
                description = attachment.description,
                syncHash = attachment.hash,
            )
        }
    } catch (e: Exception) {
        Log.e(tag, "Failed to download attachment ${attachment.hash}: ${e.message}", e)
        null
    }

    /* ------------------------------------------------------------------ *
     * Reading and downscaling                                             *
     * ------------------------------------------------------------------ */

    private class Prepared(val bytes: ByteArray, val mime: String)

    /**
     * The bytes to upload for [uri]: the original when it is already small, a
     * downscaled JPEG otherwise. The phone's own copy is never touched — this
     * only decides what the server and the browser get.
     */
    private fun prepare(uri: Uri): Prepared? {
        val bounds = decodeBounds(uri) ?: return readBytes(uri)?.let {
            // Not something BitmapFactory can read. It came from an image picker
            // and is small enough to send, so pass it through untouched rather
            // than losing it.
            Prepared(it, context.contentResolver.getType(uri) ?: "application/octet-stream")
        }

        if (bounds.first <= MAX_DIMENSION && bounds.second <= MAX_DIMENSION) {
            val original = readBytes(uri)
            if (original != null && original.size <= ORIGINAL_MAX_BYTES) {
                return Prepared(original, context.contentResolver.getType(uri) ?: "image/jpeg")
            }
        }

        val bitmap = decodeSampled(uri, bounds) ?: return null
        val oriented = applyExifRotation(uri, bitmap)
        val scaled = scaleDown(oriented)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        scaled.recycle()
        return Prepared(out.toByteArray(), "image/jpeg")
    }

    private fun decodeBounds(uri: Uri): Pair<Int, Int>? = runCatching {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        if (options.outWidth <= 0 || options.outHeight <= 0) null
        else options.outWidth to options.outHeight
    }.getOrNull()

    /**
     * Decodes at the smallest power-of-two subsample that still covers
     * [MAX_DIMENSION], so a 50-megapixel photo never has to exist in memory at
     * full size.
     */
    private fun decodeSampled(uri: Uri, bounds: Pair<Int, Int>): Bitmap? = runCatching {
        var sample = 1
        while (bounds.first / (sample * 2) >= MAX_DIMENSION && bounds.second / (sample * 2) >= MAX_DIMENSION) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }.getOrNull()

    /**
     * Bakes in the orientation the EXIF tag describes. Re-encoding drops that
     * tag, so without this every photo taken in portrait would arrive in the
     * browser lying on its side.
     */
    private fun applyExifRotation(uri: Uri, bitmap: Bitmap): Bitmap = runCatching {
        val orientation = context.contentResolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            else -> return@runCatching bitmap
        }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated != bitmap) bitmap.recycle()
        rotated
    }.getOrDefault(bitmap)

    private fun scaleDown(bitmap: Bitmap): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= MAX_DIMENSION) return bitmap
        val ratio = MAX_DIMENSION.toFloat() / longest
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
        if (scaled != bitmap) bitmap.recycle()
        return scaled
    }

    private fun readBytes(uri: Uri): ByteArray? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    }.getOrNull()

    /**
     * Only what the browser is told to expect. The server decides for itself
     * what it stored, by sniffing the bytes it was actually sent.
     */
    private fun mimeOf(image: Attachment): String =
        runCatching { context.contentResolver.getType(Uri.parse(image.path)) }.getOrNull() ?: "image/jpeg"

    private fun extensionFor(mime: String): String = when (mime) {
        "image/png" -> ".png"
        "image/gif" -> ".gif"
        "image/webp" -> ".webp"
        else -> ".jpg"
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
