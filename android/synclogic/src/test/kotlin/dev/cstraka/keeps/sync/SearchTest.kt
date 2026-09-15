package dev.cstraka.keeps.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SearchTest {

    private fun note(id: String, title: String, body: String, updatedAt: Long) =
        newNote(id = id, title = title, body = body, updatedAt = updatedAt)

    @Test
    fun prefixBeatsSubstringBeatsBody() {
        val notes = listOf(
            note("body", title = "zzz", body = "has apple inside", updatedAt = 300L),
            note("sub", title = "pineapple pie", body = "", updatedAt = 200L),
            note("pre", title = "Apple tart", body = "", updatedAt = 100L),
        )
        val ranked = rankNotes("apple", notes)
        assertEquals(listOf("pre", "sub", "body"), ranked.map { it.id })
    }

    @Test
    fun recencyBreaksTiesWithinATier() {
        val notes = listOf(
            note("old", title = "apple old", body = "", updatedAt = 100L),
            note("new", title = "apple new", body = "", updatedAt = 200L),
        )
        val ranked = rankNotes("apple", notes)
        assertEquals(listOf("new", "old"), ranked.map { it.id })
    }

    @Test
    fun nonMatchesAreDroppedAndMatchingIsCaseInsensitive() {
        val notes = listOf(
            note("miss", title = "banana", body = "cherry", updatedAt = 100L),
            note("hit", title = "APPLE", body = "", updatedAt = 100L),
        )
        val ranked = rankNotes("  Apple ", notes)
        assertEquals(listOf("hit"), ranked.map { it.id })
    }

    @Test
    fun blankQueryPreservesCallerOrder() {
        val notes = listOf(
            note("a", title = "b", body = "", updatedAt = 100L),
            note("b", title = "a", body = "", updatedAt = 300L),
        )
        assertEquals(listOf("a", "b"), rankNotes("   ", notes).map { it.id })
        assertTrue(rankNotes("", emptyList()).isEmpty())
    }
}
