package dev.cstraka.keeps.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import dev.cstraka.keeps.sync.Attachment
import dev.cstraka.keeps.sync.NoteLimits
import dev.cstraka.keeps.sync.isAttachment
import java.io.ByteArrayOutputStream
import java.util.UUID

private const val FULL_MAX_DIM = 1600
private const val THUMB_MAX_DIM = 256

/** Decoded byte estimate for a base64 data URL (4/3 inflation). */
fun dataUrlBytes(dataUrl: String): Int = (dataUrl.length * 3 + 3) / 4

private fun scaled(source: Bitmap, maxDim: Int): Bitmap {
    val longest = maxOf(source.width, source.height)
    if (longest <= maxDim) return source
    val scale = maxDim.toFloat() / longest
    return Bitmap.createScaledBitmap(
        source,
        maxOf(1, (source.width * scale).toInt()),
        maxOf(1, (source.height * scale).toInt()),
        true,
    )
}

private fun jpeg(bitmap: Bitmap, quality: Int): ByteArray {
    val out = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
    return out.toByteArray()
}

private fun dataUrlOf(bytes: ByteArray): String =
    "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)

/**
 * Downscale raw image bytes into an Attachment. Returns null when the
 * bytes are not a decodable image or still exceed caps after downscaling.
 */
fun processImageBytes(raw: ByteArray, name: String): Attachment? {
    val decoded = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return null
    var full = jpeg(scaled(decoded, FULL_MAX_DIM), 85)
    if (full.size > NoteLimits.MAX_ATTACHMENT_BYTES) {
        full = jpeg(scaled(decoded, FULL_MAX_DIM), 60)
    }
    val thumb = jpeg(scaled(decoded, THUMB_MAX_DIM), 70)
    val attachment = Attachment(
        id = UUID.randomUUID().toString(),
        name = name.take(NoteLimits.MAX_ATTACHMENT_NAME).ifEmpty { "photo.jpg" },
        mime = "image/jpeg",
        size = full.size.toLong(),
        dataUrl = dataUrlOf(full),
        thumbUrl = dataUrlOf(thumb),
    )
    return attachment.takeIf(::isAttachment)
}

/** Decode a data-URL attachment to a Bitmap; null when corrupt. */
fun attachmentBitmap(dataUrl: String): Bitmap? {
    val payload = dataUrl.substringAfter(";base64,", missingDelimiterValue = "")
    if (payload.isEmpty()) return null
    return try {
        val bytes = Base64.decode(payload, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    } catch (_: IllegalArgumentException) {
        null
    }
}
