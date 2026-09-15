package dev.cstraka.keeps.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.cstraka.keeps.sync.Drawing
import dev.cstraka.keeps.sync.DrawingPoint
import dev.cstraka.keeps.sync.DrawingStroke
import dev.cstraka.keeps.sync.Label
import dev.cstraka.keeps.sync.Note
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device verification for the drawings UI: cards resolve markers to
 * thumbnails, and the editor round-trips a finger stroke into a saved
 * drawing plus a body marker.
 */
@RunWith(AndroidJUnit4::class)
class DrawingUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val strokes = listOf(
        DrawingStroke("#ffffff", 7.0, listOf(DrawingPoint(0.1, 0.1), DrawingPoint(0.9, 0.9))),
    )

    private fun notesState(
        vararg notes: Note,
        drawings: Map<String, Drawing> = emptyMap(),
        labels: List<Label> = emptyList(),
    ) = NotesUiState(notes = notes.toList(), drawings = drawings, labels = labels)

    private fun screen(
        state: NotesUiState,
        onSaveDrawing: (String, List<DrawingStroke>) -> Unit = { _, _ -> },
    ) {
        compose.setContent {
            NotesScreen(
                state = state,
                needsLogin = false,
                deviceName = "test",
                onQuery = {},
                onFilter = {},
                onCreate = { _, _, _, _, _ -> },
                onOpenEditor = {},
                onCloseEditor = {},
                onSave = { _, _, _, _ -> },
                onSaveDrawing = onSaveDrawing,
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
    fun card_rendersDrawingThumbnailForKnownMarker() {
        val note = Note(id = "n1", body = "hello\n![drawing](d1)")
        screen(notesState(note, drawings = mapOf("d1" to Drawing(id = "d1", strokes = strokes))))
        compose.onNodeWithText("hello").assertIsDisplayed()
        // useUnmergedTree: the card's clickable merges the canvas semantics.
        compose.onNodeWithTag("drawing-thumbnail", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun card_showsPlaceholderForUnsyncedMarker() {
        val note = Note(id = "n1", body = "![drawing](missing)")
        screen(notesState(note))
        compose.onNodeWithText("[drawing — not synced yet]").assertIsDisplayed()
    }

    @Test
    fun editor_drawButtonSavesStrokeAndInsertsMarker() {
        var savedId: String? = null
        var savedStrokes: List<DrawingStroke>? = null
        compose.setContent {
            NoteDialog(
                title = "Edit note",
                initialTitle = "",
                initialBody = "hi",
                initialColor = "default",
                onDismiss = {},
                onConfirm = { _, _, _, _, _, _ -> },
                onSaveDrawing = { id, s -> savedId = id; savedStrokes = s },
            )
        }
        compose.onNodeWithText("Draw").performClick()
        compose.onNodeWithTag("draw-canvas").performTouchInput { swipeUp() }
        compose.onNodeWithTag("drawing-save").performClick()

        val id = savedId ?: throw AssertionError("drawing was not saved")
        val got = savedStrokes ?: throw AssertionError("no strokes saved")
        assert(got.size == 1) { "expected 1 stroke, got ${got.size}" }
        assert(got[0].points.size >= 2) { "stroke has no points" }
        // The marker lands on its own line so sync and render agree.
        compose.onNodeWithText("hi\n![drawing]($id)", substring = true).assertIsDisplayed()
    }

    @Test
    fun card_rendersLabelChipsAndReminderRow() {
        val note = Note(
            id = "n1", title = "t",
            labelIds = listOf("l1"),
            reminderAt = System.currentTimeMillis() + 3_600_000L,
        )
        screen(notesState(note, labels = listOf(Label(id = "l1", name = "home"))))
        // "home" renders twice: the card chip and the drawer filter entry.
        assert(
            compose.onAllNodesWithText("home", substring = false)
                .fetchSemanticsNodes().size == 2,
        ) { "expected card chip + drawer filter for 'home'" }
        // Reminder row shows the fire time; future times are not overdue.
        assert(
            compose.onAllNodesWithText("Overdue", substring = true)
                .fetchSemanticsNodes().isEmpty(),
        ) { "future reminder must not show Overdue" }
    }

    @Test
    fun card_highlightsOverdueReminder() {
        val note = Note(
            id = "n1", title = "t",
            reminderAt = System.currentTimeMillis() - 60_000L,
        )
        screen(notesState(note))
        compose.onNodeWithText("Overdue", substring = true).assertIsDisplayed()
    }

    @Test
    fun editor_showsLabelPickerAndReminderRow() {
        compose.setContent {
            NoteDialog(
                title = "Edit note",
                initialTitle = "",
                initialBody = "hi",
                initialColor = "default",
                allLabels = listOf(Label(id = "l1", name = "home")),
                onDismiss = {},
                onConfirm = { _, _, _, _, _, _ -> },
                onSaveDrawing = { _, _ -> },
            )
        }
        compose.onNodeWithText("Labels").assertIsDisplayed()
        compose.onNodeWithText("No reminder").assertIsDisplayed()
        // Opening the picker lists existing labels with a create field.
        compose.onNodeWithText("Edit").performClick()
        compose.onNodeWithText("home").assertIsDisplayed()
        compose.onNodeWithText("New label").assertIsDisplayed()
    }

    @Test
    fun drawer_listsLabelsForFiltering() {
        screen(notesState(labels = listOf(Label(id = "l1", name = "home"))))
        compose.onNodeWithContentDescription("Menu").performClick()
        compose.onNodeWithText("Labels").assertIsDisplayed()
        compose.onNodeWithText("home").assertIsDisplayed()
    }
}
