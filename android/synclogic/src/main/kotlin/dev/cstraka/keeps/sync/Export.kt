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

/**
 * Import merge for a backup file: LWW per id against local rows, exactly
 * like the web `importJson` (`mergeNoteLists(loadAll, incoming)`). Rows
 * that fail [isNote] are dropped — a single corrupt row must not sink the
 * rest of the file. Returns the full merged list; the caller writes back
 * only rows that differ and stamps them fresh so the push lane sees them
 * as dirty.
 */
fun mergeImportNotes(local: List<Note>, incoming: List<Note>): List<Note> =
    mergeNoteLists(local, incoming.filter(::isNote))
