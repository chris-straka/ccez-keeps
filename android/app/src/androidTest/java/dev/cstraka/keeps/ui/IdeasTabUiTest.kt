package dev.cstraka.keeps.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.cstraka.keeps.sync.Label
import dev.cstraka.keeps.sync.Note
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Notes | Ideas tabs: ideas live in their own tab, + there captures an idea. */
@RunWith(AndroidJUnit4::class)
class IdeasTabUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val labels = listOf(Label(id = "i1", name = "Ideas"))

    private fun screen(
        filter: NoteFilter,
        notes: List<Note> = emptyList(),
        labelFilter: String? = null,
        onFilter: (NoteFilter) -> Unit = {},
        onIdea: () -> Unit = {},
        onComposerOpen: (Boolean) -> Unit = {},
    ) {
        compose.setContent {
            NotesScreen(
                state = NotesUiState(
                    notes = notes, filter = filter, labels = labels, labelFilter = labelFilter,
                ),
                needsLogin = false,
                deviceName = "test",
                onQuery = {},
                onFilter = onFilter,
                onCreate = { _, _, _, _, _, _, _ -> },
                onComposerOpen = onComposerOpen,
                onIdea = onIdea,
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
    fun ideasTab_selectedAndPlusCapturesAnIdea() {
        var idea = false
        var composer = false
        screen(
            NoteFilter.IDEAS,
            notes = listOf(Note(id = "n1", title = "Tide game", labelIds = listOf("i1"))),
            onIdea = { idea = true },
            onComposerOpen = { composer = it },
        )
        compose.onNodeWithTag("Tab-Ideas").assertIsSelected()
        compose.onNodeWithText("Tide game").assertIsDisplayed()
        compose.onNodeWithTag("Tab-Notes").assertIsDisplayed()
        // The FAB (not the top-bar bulb): the last "New idea" node.
        compose.onAllNodes(hasContentDescription("New idea"))
            .let { it[it.fetchSemanticsNodes().size - 1] }.performClick()
        assertTrue(idea)
        assertFalse(composer)
    }

    @Test
    fun notesTab_switchesFilterAndPlusTakesANote() {
        var picked: NoteFilter? = null
        var composer = false
        screen(NoteFilter.NOTES, onFilter = { picked = it }, onComposerOpen = { composer = it })
        compose.onNodeWithTag("Tab-Notes").assertIsSelected()
        compose.onNodeWithContentDescription("Take a note").performClick()
        assertTrue(composer)
        compose.onNodeWithTag("Tab-Ideas").performClick()
        assertEquals(NoteFilter.IDEAS, picked)
    }

    @Test
    fun tabs_hiddenOnOtherViews() {
        screen(NoteFilter.ARCHIVE)
        compose.onNodeWithTag("Tab-Ideas").assertDoesNotExist()
    }

    @Test
    fun tabs_hiddenUnderALabelFilter() {
        screen(NoteFilter.NOTES, labelFilter = "i1")
        compose.onNodeWithTag("Tab-Ideas").assertDoesNotExist()
    }
}
