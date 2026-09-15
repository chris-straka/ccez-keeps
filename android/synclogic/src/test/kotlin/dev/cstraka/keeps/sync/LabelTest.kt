package dev.cstraka.keeps.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LabelTest {

    @Test
    fun isLabel_enforcesNameAndColorCaps() {
        assertTrue(isLabel(newLabel(id = "l1", name = "home")))
        assertFalse(isLabel(newLabel(id = "l1", name = "")))
        assertFalse(isLabel(newLabel(id = "l1", name = "x".repeat(121))))
        assertTrue(isLabel(newLabel(id = "l1", name = "x".repeat(120))))
        assertFalse(isLabel(newLabel(id = "l1", name = "ok", color = "x".repeat(33))))
    }

    @Test
    fun pickLabelWinner_newerUpdatedAtWinsEitherSide() {
        val old = newLabel(id = "l", name = "old", updatedAt = 100L)
        val new = newLabel(id = "l", name = "new", updatedAt = 200L)
        assertEquals("new", pickLabelWinner(old, new).name)
        assertEquals("new", pickLabelWinner(new, old).name)
    }

    @Test
    fun pickLabelWinner_equalTimestampsBreakTiesDeterministically() {
        val x = newLabel(id = "l", name = "x", updatedAt = 100L)
        val y = newLabel(id = "l", name = "y", updatedAt = 100L)
        assertEquals("y", pickLabelWinner(x, y).name)
        assertEquals("y", pickLabelWinner(y, x).name)
    }

    @Test
    fun pickLabelWinner_rejectsIdMismatch() {
        assertFailsWith<IllegalArgumentException> {
            pickLabelWinner(newLabel(id = "a", updatedAt = 1L), newLabel(id = "b", updatedAt = 2L))
        }
    }

    @Test
    fun mergeLabelLists_convergesWithNoDuplicates() {
        val a = listOf(
            newLabel(id = "1", name = "A-new", updatedAt = 300L),
            newLabel(id = "2", name = "A", updatedAt = 100L),
        )
        val b = listOf(
            newLabel(id = "1", name = "B-old", updatedAt = 200L),
            newLabel(id = "3", name = "B", updatedAt = 100L),
        )
        val merged = mergeLabelLists(a, b)
        assertEquals(3, merged.size)
        assertEquals("A-new", merged.first { it.id == "1" }.name)
    }

    @Test
    fun labelSync_dtosUseFrozenKeys() {
        val dirty = listOf(
            newLabel(id = "1", name = "live", updatedAt = 100L),
            newLabel(id = "2", name = "gone", updatedAt = 200L, deleted = true),
        )
        val decoded: LabelSyncRequest = apiJson.decodeFromString(encodeLabelSyncRequest(dirty, since = 7L))
        assertEquals(listOf("1"), decoded.upserts.map { it.id })
        assertEquals(listOf("2"), decoded.tombstones.map { it.id })
        assertEquals(7L, decoded.since)
        val raw = encodeLabelSyncRequest(dirty, since = 0L)
        assertTrue(raw.contains("\"upserts\"") && raw.contains("\"updatedAt\""))
        val pull = decodeLabelPullResponse("""{"labels":[],"cursor":9}""")
        assertEquals(9L, pull.cursor)
    }

    @Test
    fun note_labelAndReminderFieldsRoundTrip() {
        val note = newNote(
            id = "n1", title = "t", body = "b", updatedAt = 5L,
            labelIds = listOf("l1", "l2"), reminderAt = 123456L,
        )
        assertTrue(isNote(note))
        val decoded: Note = apiJson.decodeFromString(apiJson.encodeToString(Note.serializer(), note))
        assertEquals(note, decoded)
        assertEquals(listOf("l1", "l2"), decoded.labelIds)
        assertEquals(123456L, decoded.reminderAt)
    }

    @Test
    fun note_labelAndReminderJsonKeysAreFrozen() {
        val raw = apiJson.encodeToString(
            Note.serializer(),
            newNote(id = "n1", labelIds = listOf("l1"), reminderAt = 9L),
        )
        assertTrue(raw.contains("\"labelIds\""))
        assertTrue(raw.contains("\"reminderAt\""))
        // Defaults decode when the server omits the new keys (old rows).
        val legacy: Note = apiJson.decodeFromString(
            """{"id":"n1","title":"","body":"","color":"default","pinned":false,"archived":false,"updatedAt":1,"deleted":false}""",
        )
        assertEquals(emptyList(), legacy.labelIds)
        assertEquals(null, legacy.reminderAt)
    }

    @Test
    fun isNote_rejectsOverlongLabelsAndNegativeReminder() {
        assertFalse(isNote(newNote(id = "n", labelIds = List(21) { "l$it" })))
        assertFalse(isNote(newNote(id = "n", labelIds = listOf("x".repeat(65)))))
        assertFalse(isNote(newNote(id = "n", reminderAt = -1L)))
        assertTrue(isNote(newNote(id = "n", reminderAt = 0L)))
        assertTrue(isNote(newNote(id = "n")))
    }
}
