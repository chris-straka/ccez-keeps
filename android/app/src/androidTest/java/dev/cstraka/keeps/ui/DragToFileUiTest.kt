package dev.cstraka.keeps.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.cstraka.keeps.sync.Label
import dev.cstraka.keeps.sync.Note
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device verification for long-press drag-to-file on note cards:
 * the Archive/Trash/label drop bar and the drop-to-callback routing.
 */
@RunWith(AndroidJUnit4::class)
class DragToFileUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val notes = listOf(
        Note(id = "n1", title = "Alpha", body = "first"),
        Note(id = "n2", title = "Beta", body = "second"),
    )
    private val labels = listOf(
        Label(id = "l1", name = "Errands"),
        Label(id = "l2", name = "Ideas"),
    )

    private fun screen(
        state: NotesUiState = NotesUiState(notes = notes, labels = labels),
        onOpenEditor: (Note?) -> Unit = {},
    ) {
        compose.setContent {
            NotesScreen(
                state = state,
                needsLogin = false,
                deviceName = "test",
                onQuery = {},
                onFilter = {},
                onCreate = { _, _, _, _, _, _, _ -> },
                onOpenEditor = onOpenEditor,
                onCloseEditor = {},
                onSave = { _, _, _, _, _, _ -> },
                onSaveDrawing = { _, _ -> },
                onPin = {},
                onArchive = {},
                onTrash = {},
                onRestore = {},
                onDeleteForever = {},
                onSyncNow = {},
                onSignOut = {},
                deleteError = false,
                onClearDeleteError = {},
            )
        }
    }

    @Test
    fun tap_stillOpensEditor() {
        var opened: Note? = null
        screen(onOpenEditor = { opened = it })
        compose.onNodeWithText("Alpha").performClick()
        assertEquals("n1", opened?.id)
    }

    @Test
    fun dropTargets_hiddenWhenNotDragging() {
        screen()
        compose.onNodeWithTag("DropTargets", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Drop on Archive").assertDoesNotExist()
        compose.onNodeWithContentDescription("Drop on Trash").assertDoesNotExist()
    }

    @Test
    fun dropTargets_showArchiveTrashAndEveryLabel() {
        compose.setContent {
            NoteDropTargets(labels = labels, onDrop = {})
        }
        compose.onNodeWithContentDescription("Drop on Archive").assertIsDisplayed()
        compose.onNodeWithContentDescription("Drop on Trash").assertIsDisplayed()
        compose.onNodeWithContentDescription("Drop on label Errands").assertIsDisplayed()
        compose.onNodeWithContentDescription("Drop on label Ideas").assertIsDisplayed()
        compose.onNodeWithText("Archive").assertIsDisplayed()
        compose.onNodeWithText("Trash").assertIsDisplayed()
        compose.onNodeWithText("Errands").assertIsDisplayed()
    }

    @Test
    fun dropTargets_cancelButtonCallsOnCancel() {
        var cancelled = false
        compose.setContent {
            NoteDropTargets(labels = labels, onDrop = {}, onCancel = { cancelled = true })
        }
        compose.onNodeWithContentDescription("Hide drop targets").performClick()
        assertTrue(cancelled)
    }

    @Test
    fun dropTargets_withoutLabelsShowOnlyArchiveAndTrash() {
        compose.setContent {
            NoteDropTargets(labels = emptyList(), onDrop = {})
        }
        compose.onNodeWithContentDescription("Drop on Archive").assertIsDisplayed()
        compose.onNodeWithContentDescription("Drop on Trash").assertIsDisplayed()
        compose.onNodeWithTag("DropTargets", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun applyDrop_archiveCallsOnArchive() {
        var archived: Note? = null
        applyNoteDrop(
            target = NoteDropTarget.Archive,
            note = notes[0],
            labels = labels,
            onArchive = { archived = it },
            onTrash = { throw AssertionError("wrong callback") },
            onAddLabel = { _, _ -> throw AssertionError("wrong callback") },
        )
        assertEquals("n1", archived?.id)
    }

    @Test
    fun applyDrop_trashCallsOnTrash() {
        var trashed: Note? = null
        applyNoteDrop(
            target = NoteDropTarget.Trash,
            note = notes[0],
            labels = labels,
            onArchive = { throw AssertionError("wrong callback") },
            onTrash = { trashed = it },
            onAddLabel = { _, _ -> throw AssertionError("wrong callback") },
        )
        assertEquals("n1", trashed?.id)
    }

    @Test
    fun applyDrop_newLabelCallsOnAddLabel() {
        var added: Pair<Note, Label>? = null
        applyNoteDrop(
            target = NoteDropTarget.LabelDrop("l1"),
            note = notes[0],
            labels = labels,
            onArchive = { throw AssertionError("wrong callback") },
            onTrash = { throw AssertionError("wrong callback") },
            onAddLabel = { n, l -> added = n to l },
        )
        assertEquals("n1", added?.first?.id)
        assertEquals("l1", added?.second?.id)
    }

    @Test
    fun applyDrop_existingLabelIsNoop() {
        var called = false
        applyNoteDrop(
            target = NoteDropTarget.LabelDrop("l1"),
            note = notes[0].copy(labelIds = listOf("l1")),
            labels = labels,
            onArchive = { called = true },
            onTrash = { called = true },
            onAddLabel = { _, _ -> called = true },
        )
        assertTrue(!called)
    }

    @Test
    fun applyDrop_unknownLabelIsNoop() {
        var called = false
        applyNoteDrop(
            target = NoteDropTarget.LabelDrop("nope"),
            note = notes[0],
            labels = labels,
            onArchive = { called = true },
            onTrash = { called = true },
            onAddLabel = { _, _ -> called = true },
        )
        assertTrue(!called)
    }
}
