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

    @Test
    fun labelNamesMatchInExtrasTierAndDanglingIdsStayInert() {
        val notes = listOf(
            newNote(id = "tagged", title = "t", body = "b", labelIds = listOf("l1"), updatedAt = 100L),
            newNote(id = "dangling", title = "t", body = "b", labelIds = listOf("gone"), updatedAt = 200L),
            newNote(id = "body", title = "t", body = "weekend errands", updatedAt = 50L),
            newNote(id = "none", title = "t", body = "b", updatedAt = 300L),
        )
        val names = mapOf("l1" to "Weekend")
        assertEquals(listOf("body", "tagged"), rankNotes("weekend", notes, names).map { it.id })
        assertEquals(listOf("body"), rankNotes("weekend", notes).map { it.id })
    }

    @Test
    fun checklistAndAttachmentNamesMatchBelowBody() {
        val notes = listOf(
            newNote(
                id = "extra", title = "Trip", body = "plain", updatedAt = 100L,
                checklist = listOf(ChecklistItem(id = "c1", text = "buy oatmilk", checked = false)),
            ),
            newNote(
                id = "file", title = "Trip", body = "plain", updatedAt = 200L,
                attachments = listOf(
                    Attachment(
                        id = "a1", name = "oatmilk-label.png", mime = "image/png", size = 4L,
                        dataUrl = "data:image/png;base64,iVBORw==",
                        thumbUrl = "data:image/png;base64,iVBORw==",
                    ),
                ),
            ),
            note("body", title = "Trip", body = "oatmilk latte", updatedAt = 50L),
            note("none", title = "Trip", body = "plain", updatedAt = 300L),
        )
        assertEquals(listOf("body", "file", "extra"), rankNotes("oatmilk", notes).map { it.id })
    }
}
