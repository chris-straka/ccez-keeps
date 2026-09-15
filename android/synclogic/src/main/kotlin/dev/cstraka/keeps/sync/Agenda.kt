package dev.cstraka.keeps.sync

/**
 * Reminders agenda membership + ordering, shared so the ViewModel bucket
 * and its unit tests cannot drift. Membership spans notes + archive (a
 * firing reminder must not hide); order is fire time, overdue first.
 */
fun isAgendaNote(note: Note): Boolean = !note.deleted && note.reminderAt != null

fun sortAgenda(notes: List<Note>): List<Note> =
    notes.filter(::isAgendaNote).sortedBy { it.reminderAt ?: Long.MAX_VALUE }
