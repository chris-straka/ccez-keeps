package dev.cstraka.keeps.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.cstraka.keeps.sync.Label
import dev.cstraka.keeps.sync.Note
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device verification for the list/grid toggle, the Reminders FAB
 * with its reminder-first composer, and drawer row icons.
 */
@RunWith(AndroidJUnit4::class)
class ListGridViewUiTest {

    @get:Rule
    val compose = createComposeRule()

    private fun screen(state: NotesUiState, composerOpen: Boolean = false) {
        compose.setContent {
            NotesScreen(
                state = state,
                needsLogin = false,
                deviceName = "test",
                onQuery = {},
                onFilter = {},
                onCreate = { _, _, _, _, _, _, _ -> },
                composerOpen = composerOpen,
                onComposerOpen = {},
                onOpenEditor = {},
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

    private val notes = listOf(
        Note(id = "n1", title = "Alpha", body = "first"),
        Note(id = "n2", title = "Beta", body = "second"),
    )

    @Test
    fun toggle_switchesBetweenListAndGrid() {
        screen(NotesUiState(notes = notes))
        compose.onNodeWithContentDescription("List view").assertIsDisplayed()
        compose.onNodeWithText("Alpha").assertIsDisplayed()
        compose.onNodeWithContentDescription("List view").performClick()
        compose.onNodeWithContentDescription("Grid view").assertIsDisplayed()
        compose.onNodeWithText("Alpha").assertIsDisplayed()
        compose.onNodeWithText("Beta").assertIsDisplayed()
        compose.onNodeWithContentDescription("Grid view").performClick()
        compose.onNodeWithContentDescription("List view").assertIsDisplayed()
    }

    @Test
    fun fab_showsInRemindersButNotArchive() {
        screen(NotesUiState(filter = NoteFilter.REMINDERS))
        compose.onNodeWithContentDescription("Take a note").assertIsDisplayed()
        screen(NotesUiState(filter = NoteFilter.ARCHIVE))
        compose.onAllNodesWithContentDescription("Take a note").assertCountEquals(0)
    }

    @Test
    fun composer_inRemindersStartsWithDatePicker() {
        screen(NotesUiState(filter = NoteFilter.REMINDERS), composerOpen = true)
        compose.onNodeWithText("Take a note…").assertIsDisplayed()
        compose.onNodeWithText("Next").assertIsDisplayed()
    }

    @Test
    fun composer_inNotesStartsWithoutDatePicker() {
        screen(NotesUiState(filter = NoteFilter.NOTES), composerOpen = true)
        compose.onNodeWithText("Take a note…").assertIsDisplayed()
        compose.onAllNodesWithText("Next").assertCountEquals(0)
    }

    @Test
    fun drawer_showsAllRowsWithLabels() {
        screen(
            NotesUiState(
                labels = listOf(Label(id = "l1", name = "Errands")),
            ),
        )
        compose.onNodeWithContentDescription("Menu").performClick()
        compose.onNodeWithText("Notes").assertIsDisplayed()
        compose.onNodeWithText("Reminders").assertIsDisplayed()
        compose.onNodeWithText("Archive").assertIsDisplayed()
        compose.onNodeWithText("Trash").assertIsDisplayed()
        compose.onNodeWithText("Errands").assertIsDisplayed()
        compose.onNodeWithText("Settings").assertIsDisplayed()
    }
}
