package dev.cstraka.keeps.sync

/**
 * Ranked note search. Rule: title-prefix beats title-substring beats body
 * match beats checklist-item / attachment-name match; recency (updatedAt
 * desc) breaks ties within a tier. Case-insensitive. A blank query
 * preserves the caller's order (the ViewModel feeds Room order). Must keep
 * the same tiers as web/store/search.ts rankNotes.
 */
fun rankNotes(query: String, notes: List<Note>): List<Note> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return notes.toList()
    data class Scored(val note: Note, val tier: Int)
    return notes.mapNotNull { note ->
        val title = note.title.lowercase()
        val body = note.body.lowercase()
        val tier = when {
            title.startsWith(q) -> 0
            q in title -> 1
            q in body -> 2
            note.matchesExtras(q) -> 3
            else -> null
        }
        tier?.let { Scored(note, it) }
    }.sortedWith(compareBy({ it.tier }, { -it.note.updatedAt }))
        .map { it.note }
}

/** True when any checklist item text or attachment name contains q. */
fun Note.matchesExtras(q: String): Boolean {
    for (item in checklist ?: emptyList()) {
        if (q in item.text.lowercase()) return true
    }
    for (a in attachments) {
        if (q in a.name.lowercase()) return true
    }
    return false
}
