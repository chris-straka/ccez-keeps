package dev.cstraka.keeps.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExportTest {

    @Test
    fun export_wrapsNotesWithVersion() {
        val json = exportNotes(
            listOf(newNote(id = "1", title = "t", repeat = "daily", labelIds = listOf("l"))),
        )
        assertTrue(json.contains("\"version\":1"))
        assertTrue(json.contains("\"repeat\":\"daily\""))
        val parsed = parseExport(json)
        assertEquals(1, parsed.version)
        assertEquals(listOf("1"), parsed.notes.map { it.id })
        assertEquals(listOf("l"), parsed.notes[0].labelIds)
    }

    @Test
    fun export_matchesWebShape() {
        // Shape probe against the web exportJson layout (key order free).
        val web = """{"version":1,"notes":[{"id":"w","title":"","body":"","color":"default","pinned":false,"archived":false,"updatedAt":5,"deleted":false,"labelIds":[],"reminderAt":null,"repeat":null}]}"""
        val parsed = parseExport(web)
        assertEquals("w", parsed.notes[0].id)
        assertTrue(isNote(parsed.notes[0]))
    }

    @Test
    fun export_emptyListRoundTrips() {
        assertEquals(0, parseExport(exportNotes(emptyList())).notes.size)
    }
}
