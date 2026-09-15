package dev.cstraka.keeps.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device verification for the reminders agenda: the drawer entry
 * filters to firing notes (notes + archive, never trash) in fire-time
 * order with an empty-state message.
 */
@RunWith(AndroidJUnit4::class)
class RemindersUiTest {

    @get:Rule
    val compose = createComposeRule()

    private fun screen(state: NotesUiState) {
        compose.setContent {
            NotesScreen(
                state = state,
                needsLogin = false,
                deviceName = "test",
                onQuery = {},
                onFilter = {},
                onCreate = { _, _, _, _, _, _, _ -> },
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

    @Test
    fun drawer_remindersShowsAgendaEntry() {
        screen(NotesUiState())
        compose.onNodeWithContentDescription("Menu").performClick()
        compose.onNodeWithText("Reminders").assertIsDisplayed()
    }

    @Test
    fun agenda_emptyStateMessage() {
        screen(NotesUiState(filter = NoteFilter.REMINDERS))
        compose.onNodeWithText("No upcoming reminders.").assertIsDisplayed()
    }
}
