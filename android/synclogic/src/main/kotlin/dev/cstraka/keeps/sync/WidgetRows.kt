package dev.cstraka.keeps.sync

/**
 * Notes-list widget row text, shared so the RemoteViews factory and its
 * unit tests cannot drift. Title wins; otherwise the first non-blank body
 * line, then the first non-blank checklist item, then "Untitled".
 */
fun widgetRowTitle(note: Note): String {
    if (note.title.isNotBlank()) return note.title
    return note.body.lineSequence().firstOrNull { it.isNotBlank() }
        ?: note.checklist?.firstOrNull { it.text.isNotBlank() }?.text
        ?: "Untitled"
}

/** Snippet under a titled row; blank when the title already says it all. */
fun widgetRowSnippet(note: Note): String {
    if (note.title.isBlank()) return ""
    return note.body.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
}

/** Live notes in NOTES view order for the widget, capped for the host. */
fun widgetRows(notes: List<Note>, maxRows: Int = 20): List<Note> =
    notes.filter { !it.deleted && !it.archived }
        .sortedWith(compareByDescending<Note> { it.pinned }.thenByDescending { it.updatedAt })
        .take(maxRows)
