package dev.cstraka.keeps.sync

import kotlinx.serialization.Serializable

/**
 * Kotlin port of shared/note.ts (frozen contract). Field names and JSON keys
 * match the web/Worker shapes exactly so both clients speak one API.
 */
@Serializable
data class Note(
    val id: String,
    val title: String = "",
    val body: String = "",
    /** Color key, e.g. "default" | "red" | "green" | "blue" | "yellow". */
    val color: String = "default",
    val pinned: Boolean = false,
    val archived: Boolean = false,
    /** Unix epoch milliseconds, client-assigned. Last-write-wins key. */
    val updatedAt: Long = 0L,
    /** Soft delete. True = tombstone; tombstones sync like notes. */
    val deleted: Boolean = false,
    /** Label ids attached to this note (max 20, each max 64 chars). */
    val labelIds: List<String> = emptyList(),
    /** Reminder fire time, unix epoch ms; null = no reminder. */
    val reminderAt: Long? = null,
    /** Repeat rule for the reminder; null = fires once. */
    val repeat: String? = null,
    /**
     * Checklist items; null = plain text note. Rides note sync free under
     * LWW like labelIds. Missing (old rows/clients) decodes to null.
     */
    val checklist: List<ChecklistItem>? = null,
    /**
     * Image attachments as data URLs (client-downscaled). Missing
     * (old rows/clients) decodes to empty. Validated, never interpreted.
     */
    val attachments: List<Attachment> = emptyList(),
) {
    init {
        require(id.isNotEmpty()) { "Note id must not be empty" }
    }
}

/** One row of a checklist note. Client-generated id, never reused. */
@Serializable
data class ChecklistItem(
    val id: String = "",
    val text: String = "",
    val checked: Boolean = false,
)

/** One client-downscaled image attached to a note. */
@Serializable
data class Attachment(
    val id: String = "",
    /** Original file name (display + search only, never a path). */
    val name: String = "",
    /** Image MIME type, e.g. image/jpeg. */
    val mime: String = "",
    /** Byte size of the decoded dataUrl payload. */
    val size: Long = 0L,
    /** Full image, `data:image/...;base64,...` within limits. */
    val dataUrl: String = "",
    /** Small preview of the same image, same shape, tighter limit. */
    val thumbUrl: String = "",
)

object NoteLimits {
    const val MAX_LABELS = 20
    const val MAX_LABEL_ID_LENGTH = 64
    const val MAX_CHECKLIST_ITEMS = 100
    const val MAX_CHECKLIST_TEXT = 500
    const val MAX_CHECKLIST_ID_LENGTH = 64
    const val MAX_ATTACHMENTS = 10
    const val MAX_ATTACHMENT_NAME = 200
    /** Decoded-payload cap per full image, in bytes. */
    const val MAX_ATTACHMENT_BYTES = 700_000
    /** Decoded-payload cap per thumbnail, in bytes. */
    const val MAX_THUMB_BYTES = 40_000
}

private val IMAGE_DATA_URL = Regex("^data:(image/[A-Za-z0-9.+-]+);base64,([A-Za-z0-9+/=]+)$")

/** `data:image/<sub>;base64,<payload>` with a strict base64 payload. */
fun isImageDataUrl(value: String): Boolean {
    val m = IMAGE_DATA_URL.matchEntire(value) ?: return false
    return m.groupValues[2].length % 4 == 0
}

fun isChecklistItem(item: ChecklistItem): Boolean =
    item.id.isNotEmpty() &&
        item.id.length <= NoteLimits.MAX_CHECKLIST_ID_LENGTH &&
        item.text.length <= NoteLimits.MAX_CHECKLIST_TEXT

fun isAttachment(a: Attachment): Boolean {
    if (a.id.isEmpty()) return false
    if (a.name.isEmpty() || a.name.length > NoteLimits.MAX_ATTACHMENT_NAME) return false
    if (!a.mime.startsWith("image/")) return false
    if (a.size < 0L || a.size > NoteLimits.MAX_ATTACHMENT_BYTES) return false
    if (!isImageDataUrl(a.dataUrl) || !isImageDataUrl(a.thumbUrl)) return false
    // Base64 inflates by 4/3, so length * 3/4 bounds the decoded bytes.
    if ((a.dataUrl.length * 3 + 3) / 4 > NoteLimits.MAX_ATTACHMENT_BYTES) return false
    if ((a.thumbUrl.length * 3 + 3) / 4 > NoteLimits.MAX_THUMB_BYTES) return false
    return true
}

/** Client-side validation mirroring shared/note.ts isNote. */
fun isNote(note: Note): Boolean =
    note.id.isNotEmpty() &&
        note.labelIds.size <= NoteLimits.MAX_LABELS &&
        note.labelIds.all { it.length <= NoteLimits.MAX_LABEL_ID_LENGTH } &&
        (note.reminderAt == null || note.reminderAt >= 0L) &&
        (note.repeat == null || note.repeat == "daily" || note.repeat == "weekly") &&
        (note.checklist == null ||
            (note.checklist.size <= NoteLimits.MAX_CHECKLIST_ITEMS &&
                note.checklist.all(::isChecklistItem))) &&
        note.attachments.size <= NoteLimits.MAX_ATTACHMENTS &&
        note.attachments.all(::isAttachment)

val NOTE_COLORS = listOf(
    "default", "red", "orange", "yellow", "green", "teal", "blue", "purple", "pink",
)

fun newNote(
    id: String,
    title: String = "",
    body: String = "",
    color: String = "default",
    pinned: Boolean = false,
    archived: Boolean = false,
    updatedAt: Long = System.currentTimeMillis(),
    deleted: Boolean = false,
    labelIds: List<String> = emptyList(),
    reminderAt: Long? = null,
    repeat: String? = null,
    checklist: List<ChecklistItem>? = null,
    attachments: List<Attachment> = emptyList(),
): Note = Note(id, title, body, color, pinned, archived, updatedAt, deleted, labelIds, reminderAt, repeat, checklist, attachments)

/**
 * Next fire time for a repeat rule, relative to the fired time. Pure so
 * both the worker (reschedule) and tests share one computation.
 */
fun nextRepeat(firedAt: Long, rule: String): Long = when (rule) {
    "weekly" -> firedAt + 7 * 24 * 60 * 60 * 1000L
    else -> firedAt + 24 * 60 * 60 * 1000L
}
