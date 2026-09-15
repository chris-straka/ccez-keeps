package dev.cstraka.keeps.sync

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Kotlin port of shared/sync.ts (frozen conflict rule): last-write-wins on
 * updatedAt. Tiebreak: lexicographically larger JSON wins (deterministic
 * across devices). Must converge identically to the TS implementation —
 * see SyncLogicTest, which mirrors tests/sync.test.ts vector for vector.
 */
private val canonicalJson = Json { encodeDefaults = true }

fun noteJson(note: Note): String = canonicalJson.encodeToString(note)

/**
 * Winner of two versions of the same note id. Never mutates inputs. Total
 * order, symmetric in argument order.
 */
fun pickWinner(local: Note, remote: Note): Note {
    require(local.id == remote.id) { "pickWinner: id mismatch" }
    if (remote.updatedAt != local.updatedAt) {
        return if (remote.updatedAt > local.updatedAt) remote else local
    }
    return if (noteJson(remote) >= noteJson(local)) remote else local
}

/**
 * Merge two lists by id with last-write-wins. A newer tombstone
 * (deleted: true) beats an older live note and vice versa — deletion is
 * just another write. Returns a new list, no duplicates, no mutation.
 */
fun mergeNoteLists(a: List<Note>, b: List<Note>): List<Note> {
    val byId = LinkedHashMap<String, Note>()
    for (note in a + b) {
        val existing = byId[note.id]
        byId[note.id] = if (existing != null) pickWinner(existing, note) else note
    }
    return byId.values.toList()
}

/** Rows with updatedAt strictly greater than [since] (delta-pull cursor). */
fun changedSince(notes: List<Note>, since: Long): List<Note> =
    notes.filter { it.updatedAt > since }
