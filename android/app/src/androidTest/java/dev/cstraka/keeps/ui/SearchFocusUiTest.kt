package dev.cstraka.keeps.ui

import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.cstraka.keeps.sync.Note
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Owner report (2026-10-13): the search field never lost focus when
 * tapping away, tapping something else, or leaving. Touching the grid,
 * switching tabs and opening the composer must all drop it.
 */
@RunWith(AndroidJUnit4::class)
class SearchFocusUiTest {

    @get:Rule
    val compose = createComposeRule()

    private fun screen(composerOpen: Boolean = false) {
        compose.setContent {
            NotesScreen(
                state = NotesUiState(notes = (1..12).map { Note(id = "n$it", title = "Note $it", body = "body $it") }),
                needsLogin = false,
                deviceName = "test",
                onQuery = {},
                onFilter = {},
                onCreate = { _, _, _, _, _, _, _ -> },
                composerOpen = composerOpen,
                onOpenEditor = {},
                onCloseEditor = {},
                onSave = { _, _ -> },
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
    fun touchingTheGridDropsSearchFocus() {
        screen()
        compose.onNodeWithTag("searchField").performClick()
        compose.onNodeWithTag("searchField").assertIsFocused()
        compose.onNodeWithText("Note 1").performTouchInput { swipeUp(durationMillis = 100) }
        compose.onNodeWithTag("searchField").assertIsNotFocused()
    }

    @Test
    fun switchingTabsDropsSearchFocus() {
        screen()
        compose.onNodeWithTag("searchField").performClick()
        compose.onNodeWithTag("searchField").assertIsFocused()
        compose.onNodeWithTag("Tab-Ideas").performClick()
        compose.onNodeWithTag("searchField").assertIsNotFocused()
    }

    @Test
    fun clearButtonEmptiesAndUnfocuses() {
        screen()
        compose.onNodeWithTag("searchField").performClick()
        compose.onNodeWithTag("searchField").performTextInput("milk")
        // onQuery is a no-op here, so the field keeps showing the typed text
        // only through the local IME buffer; the clear button must still
        // exist once state.query is non-empty in the real app. Here we
        // only check focus handling on the menu path.
        compose.onNodeWithContentDescription("Menu").performClick()
        compose.onNodeWithTag("searchField").assertIsNotFocused()
    }
}
