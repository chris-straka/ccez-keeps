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
) {
    init {
        require(id.isNotEmpty()) { "Note id must not be empty" }
    }
}

object NoteLimits {
    const val MAX_LABELS = 20
    const val MAX_LABEL_ID_LENGTH = 64
}

/** Client-side validation mirroring shared/note.ts isNote. */
fun isNote(note: Note): Boolean =
    note.id.isNotEmpty() &&
        note.labelIds.size <= NoteLimits.MAX_LABELS &&
        note.labelIds.all { it.length <= NoteLimits.MAX_LABEL_ID_LENGTH } &&
        (note.reminderAt == null || note.reminderAt >= 0L) &&
        (note.repeat == null || note.repeat == "daily" || note.repeat == "weekly")

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
): Note = Note(id, title, body, color, pinned, archived, updatedAt, deleted, labelIds, reminderAt, repeat)

/**
 * Next fire time for a repeat rule, relative to the fired time. Pure so
 * both the worker (reschedule) and tests share one computation.
 */
fun nextRepeat(firedAt: Long, rule: String): Long = when (rule) {
    "weekly" -> firedAt + 7 * 24 * 60 * 60 * 1000L
    else -> firedAt + 24 * 60 * 60 * 1000L
}
