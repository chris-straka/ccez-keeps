package dev.cstraka.keeps.sync

import kotlin.test.Test
import kotlin.test.assertEquals

class AgendaTest {

    @Test
    fun spansNotesAndArchiveInFireTimeOrder() {
        val notes = listOf(
            newNote(id = "later", reminderAt = 9000L, updatedAt = 200L),
            newNote(id = "plain", updatedAt = 100L),
            newNote(id = "arch", archived = true, reminderAt = 5000L, updatedAt = 400L),
            newNote(id = "gone", deleted = true, reminderAt = 100L, updatedAt = 500L),
            newNote(id = "soon", reminderAt = 1000L, updatedAt = 300L),
        )
        assertEquals(listOf("soon", "arch", "later"), sortAgenda(notes).map { it.id })
    }
}
