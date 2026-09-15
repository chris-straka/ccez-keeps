package dev.cstraka.keeps.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NoteTest {

    private val goodAttachment = Attachment(
        id = "a1",
        name = "photo.png",
        mime = "image/png",
        size = 6L,
        dataUrl = "data:image/png;base64,iVBORw==",
        thumbUrl = "data:image/png;base64,iVBORw==",
    )

    @Test
    fun defaultsAreATextNoteWithNoAttachments() {
        val note = newNote(id = "a")
        assertTrue(isNote(note))
    }

    @Test
    fun acceptsPopulatedChecklistAndAttachments() {
        val note = newNote(
            id = "x",
            checklist = listOf(
                ChecklistItem(id = "c1", text = "milk", checked = false),
                ChecklistItem(id = "c2", text = "eggs", checked = true),
            ),
            attachments = listOf(goodAttachment),
        )
        assertTrue(isNote(note))
        assertTrue(isNote(newNote(id = "x", checklist = emptyList())))
    }

    @Test
    fun rejectsChecklistViolationsAndCapOverflow() {
        assertFalse(isNote(newNote(id = "x", checklist = listOf(ChecklistItem(id = "", text = "t")))))
        assertFalse(
            isNote(newNote(id = "x", checklist = listOf(ChecklistItem(id = "c", text = "x".repeat(501))))),
        )
        val many = (0 until 101).map { ChecklistItem(id = "c$it", text = "t") }
        assertFalse(isNote(newNote(id = "x", checklist = many)))
    }

    @Test
    fun rejectsAttachmentViolationsAndCapOverflow() {
        assertFalse(isNote(newNote(id = "x", attachments = listOf(goodAttachment.copy(mime = "application/pdf")))))
        assertFalse(isNote(newNote(id = "x", attachments = listOf(goodAttachment.copy(mime = "text/plain")))))
        assertFalse(
            isNote(newNote(id = "x", attachments = listOf(goodAttachment.copy(dataUrl = "https://x/y.png")))),
        )
        assertFalse(
            isNote(
                newNote(
                    id = "x",
                    attachments = listOf(goodAttachment.copy(thumbUrl = "data:image/png;base64," + "QUJD".repeat(20000))),
                ),
            ),
        )
        val many = (0 until 11).map { goodAttachment.copy(id = "a$it") }
        assertFalse(isNote(newNote(id = "x", attachments = many)))
    }

    @Test
    fun exportRoundTripKeepsNewFields() {
        val note = newNote(
            id = "r",
            checklist = listOf(ChecklistItem(id = "c1", text = "milk", checked = true)),
            attachments = listOf(goodAttachment),
        )
        val parsed = parseExport(exportNotes(listOf(note)))
        val roundTripped = parsed.notes.single()
        assertTrue(isNote(roundTripped))
        assertEquals(note.checklist, roundTripped.checklist)
        assertEquals(note.attachments, roundTripped.attachments)
    }
}
