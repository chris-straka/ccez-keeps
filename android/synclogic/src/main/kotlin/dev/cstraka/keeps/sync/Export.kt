package dev.cstraka.keeps.sync

import kotlinx.serialization.Serializable

/**
 * Portable backup shape. Identical to the web `exportJson()` output
 * (`{version: 1, notes}`) so files move between clients; import on either
 * side merges by id under LWW.
 */
@Serializable
data class NotesExport(val version: Int = 1, val notes: List<Note> = emptyList())

fun exportNotes(notes: List<Note>): String =
    apiJson.encodeToString(NotesExport.serializer(), NotesExport(notes = notes))

fun parseExport(raw: String): NotesExport =
    apiJson.decodeFromString(NotesExport.serializer(), raw)
