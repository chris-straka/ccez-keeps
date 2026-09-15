package dev.cstraka.keeps.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private fun base(
    body: String = "b",
    updatedAt: Long = 1000L,
    deleted: Boolean = false,
): Note = newNote(id = "a", title = "t", body = body, updatedAt = updatedAt, deleted = deleted)

class SyncLogicTest {

    @Test
    fun pickWinner_newerUpdatedAtWinsEitherSide() {
        val old = base(body = "old", updatedAt = 100L)
        val new = base(body = "new", updatedAt = 200L)
        assertEquals("new", pickWinner(old, new).body)
        assertEquals("new", pickWinner(new, old).body)
    }

    @Test
    fun pickWinner_equalTimestampsBreakTiesDeterministicallyEitherOrder() {
        val x = base(body = "x", updatedAt = 100L)
        val y = base(body = "y", updatedAt = 100L)
        assertEquals("y", pickWinner(x, y).body)
        assertEquals("y", pickWinner(y, x).body)
    }

    @Test
    fun pickWinner_tombstoneVsLiveIsJustAnotherWrite() {
        val live = base(deleted = false, updatedAt = 100L)
        val tombstone = base(deleted = true, updatedAt = 200L)
        assertTrue(pickWinner(live, tombstone).deleted)
        assertTrue(pickWinner(tombstone, live).deleted)
        val oldTombstone = base(deleted = true, updatedAt = 50L)
        assertEquals(false, pickWinner(oldTombstone, live).deleted)
    }

    @Test
    fun pickWinner_rejectsIdMismatch() {
        assertFailsWith<IllegalArgumentException> {
            pickWinner(newNote(id = "a", updatedAt = 1L), newNote(id = "b", updatedAt = 2L))
        }
    }

    @Test
    fun mergeNoteLists_convergesWithNoDuplicatesAndNoLostNewerEdit() {
        val deviceA = listOf(
            newNote(id = "1", body = "A-new", updatedAt = 300L),
            newNote(id = "2", body = "A", updatedAt = 100L),
        )
        val deviceB = listOf(
            newNote(id = "1", body = "B-old", updatedAt = 200L),
            newNote(id = "3", body = "B", updatedAt = 100L),
        )
        val merged = mergeNoteLists(deviceA, deviceB)
        assertEquals(3, merged.size)
        assertEquals("A-new", merged.associateBy { it.id }["1"]?.body)
        // Symmetric: merge order must not change the converged content.
        val bodies = { list: List<Note> -> list.sortedBy { it.id }.map { it.body } }
        assertEquals(bodies(merged), bodies(mergeNoteLists(deviceB, deviceA)))
    }

    @Test
    fun mergeNoteLists_intraListDuplicatesCollapseByLwwRegardlessOfOrder() {
        val older = base(body = "older", updatedAt = 100L)
        val newer = base(body = "newer", updatedAt = 200L)
        assertEquals("newer", mergeNoteLists(listOf(older, newer), emptyList())[0].body)
        assertEquals("newer", mergeNoteLists(listOf(newer, older), emptyList())[0].body)
    }

    @Test
    fun changedSince_isStrictlyGreater() {
        val notes = listOf(base(updatedAt = 100L), base(updatedAt = 101L))
        assertEquals(1, changedSince(notes, 100L).size)
        assertEquals(0, changedSince(notes, 101L).size)
    }

    @Test
    fun tiebreak_matchesTypeScriptJsonStringifyOrdering() {
        // The TS rule compares JSON.stringify output; the Kotlin port must
        // pick the same winner for the same pair. "y" > "x" in both.
        val x = base(body = "x", updatedAt = 100L)
        val y = base(body = "y", updatedAt = 100L)
        assertTrue(noteJson(y) >= noteJson(x))
        assertEquals("y", pickWinner(x, y).body)
    }
}
