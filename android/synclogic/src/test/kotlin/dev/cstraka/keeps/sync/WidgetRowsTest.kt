package dev.cstraka.keeps.sync

import kotlin.test.Test
import kotlin.test.assertEquals

class WidgetRowsTest {

    @Test
    fun titleWinsWithBodySnippet() {
        val note = newNote(id = "a", title = "Shop", body = "milk\neggs")
        assertEquals("Shop", widgetRowTitle(note))
        assertEquals("milk", widgetRowSnippet(note))
    }

    @Test
    fun untitledFallsBackToBodyThenChecklistThenUntitled() {
        val body = newNote(id = "b", body = "\nmilk")
        assertEquals("milk", widgetRowTitle(body))
        assertEquals("", widgetRowSnippet(body))
        val list = newNote(
            id = "c",
            checklist = listOf(ChecklistItem(id = "c1", text = "eggs", checked = false)),
        )
        assertEquals("eggs", widgetRowTitle(list))
        assertEquals("Untitled", widgetRowTitle(newNote(id = "d")))
    }

    @Test
    fun rowsAreLivePinnedFirstCapped() {
        val notes = listOf(
            newNote(id = "old", updatedAt = 100L),
            newNote(id = "pinned", pinned = true, updatedAt = 50L),
            newNote(id = "new", updatedAt = 300L),
            newNote(id = "arch", archived = true, updatedAt = 400L),
            newNote(id = "gone", deleted = true, updatedAt = 500L),
        )
        assertEquals(listOf("pinned", "new", "old"), widgetRows(notes).map { it.id })
        assertEquals(2, widgetRows(notes, maxRows = 2).size)
    }
}
