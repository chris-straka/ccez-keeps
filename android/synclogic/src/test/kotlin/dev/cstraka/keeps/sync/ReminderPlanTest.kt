package dev.cstraka.keeps.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReminderPlanTest {

    private val now = 1_000_000L

    @Test
    fun futureReminderOnLiveNoteSchedules() {
        val note = newNote(id = "1", reminderAt = now + 60_000L)
        val plan = planReminders(listOf(note), now)
        assertEquals(listOf(note), plan.schedule)
        assertTrue(plan.cancel.isEmpty())
    }

    @Test
    fun clearedPastAndDeletedRemindersCancel() {
        val cleared = newNote(id = "1", reminderAt = null)
        val past = newNote(id = "2", reminderAt = now - 1L)
        val boundary = newNote(id = "3", reminderAt = now)
        val deleted = newNote(id = "4", reminderAt = now + 60_000L, deleted = true)
        val plan = planReminders(listOf(cleared, past, boundary, deleted), now)
        assertTrue(plan.schedule.isEmpty())
        assertEquals(listOf("1", "2", "3", "4"), plan.cancel)
    }

    @Test
    fun emptyChangeSetPlansNothing() {
        val plan = planReminders(emptyList(), now)
        assertTrue(plan.schedule.isEmpty())
        assertTrue(plan.cancel.isEmpty())
    }

    @Test
    fun mixedBatchSplitsBothWays() {
        val live = newNote(id = "1", reminderAt = now + 1L)
        val plan = planReminders(listOf(live, newNote(id = "2", reminderAt = null)), now)
        assertEquals(listOf("1"), plan.schedule.map { it.id })
        assertEquals(listOf("2"), plan.cancel)
    }
}
