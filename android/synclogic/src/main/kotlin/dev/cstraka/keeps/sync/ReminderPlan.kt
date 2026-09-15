package dev.cstraka.keeps.sync

/**
 * Pure reminder reconcile for applied sync deltas. Reminders set on another
 * client arrive as plain note rows; without this, the phone would never
 * fire them (local saves schedule directly in the ViewModel).
 *
 * Rule: a changed, live note with a strictly future reminderAt schedules;
 * everything else changed (cleared, past-due, deleted) cancels any pending
 * fire. Past-due notes surface through the in-app overdue state instead of
 * firing instantly for history pulled down late.
 */
data class ReminderPlan(val schedule: List<Note> = emptyList(), val cancel: List<String> = emptyList())

fun planReminders(changed: List<Note>, now: Long): ReminderPlan {
    val schedule = mutableListOf<Note>()
    val cancel = mutableListOf<String>()
    for (note in changed) {
        val at = note.reminderAt
        if (!note.deleted && at != null && at > now) schedule.add(note)
        else cancel.add(note.id)
    }
    return ReminderPlan(schedule, cancel)
}
