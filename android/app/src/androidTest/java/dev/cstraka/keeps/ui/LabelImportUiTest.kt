package dev.cstraka.keeps.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.cstraka.keeps.sync.newLabel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device verification for Settings import and drawer label editing.
 * Emulator-only: cannot run headless in CI here; kept compiling so the
 * device lab covers them.
 */
@RunWith(AndroidJUnit4::class)
class LabelImportUiTest {

    @get:Rule
    val compose = createComposeRule()

    private fun screen(
        state: NotesUiState = NotesUiState(),
        onImportJson: (String) -> Unit = {},
        onRenameLabel: (String, String) -> Unit = { _, _ -> },
        onDeleteLabel: (String) -> Unit = {},
    ) {
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
                onImportJson = onImportJson,
                onRenameLabel = onRenameLabel,
                onDeleteLabel = onDeleteLabel,
            )
        }
    }

    private fun openDrawer() {
        compose.onNodeWithContentDescription("Menu").performClick()
    }

    private fun openSettings() {
        openDrawer()
        compose.onNodeWithText("Settings").performClick()
    }

    @Test
    fun settings_showsImportNextToExport() {
        screen()
        openSettings()
        compose.onNodeWithText("Export notes").assertIsDisplayed()
        compose.onNodeWithText("Import notes").assertIsDisplayed()
    }

    @Test
    fun drawer_longPressLabelOpensEditDialog() {
        val labels = listOf(newLabel(id = "l1", name = "home"))
        screen(NotesUiState(labels = labels))
        openDrawer()
        compose.onNodeWithText("home").performTouchInput { longClick() }
        compose.onNodeWithText("Edit label").assertIsDisplayed()
        compose.onNodeWithText("Rename").assertIsDisplayed()
        compose.onNodeWithText("Delete").assertIsDisplayed()
    }

    @Test
    fun labelDialog_renameDisabledWhenBlank() {
        val labels = listOf(newLabel(id = "l1", name = "home"))
        var renamed: Pair<String, String>? = null
        screen(
            NotesUiState(labels = labels),
            onRenameLabel = { id, name -> renamed = id to name },
        )
        openDrawer()
        compose.onNodeWithText("home").performTouchInput { longClick() }
        // Prefilled with the current name; clearing disables Rename.
        compose.onNodeWithTag("labelNameField").assertTextEquals("home")
        compose.onNodeWithTag("labelNameField").performTextClearance()
        compose.onNodeWithText("Rename").assertIsNotEnabled()
        compose.onNodeWithTag("labelNameField").performTextInput("work")
        compose.onNodeWithText("Rename").assertIsEnabled()
        compose.onNodeWithText("Rename").performClick()
        assertEquals("l1" to "work", renamed)
    }

    @Test
    fun labelDialog_deleteCallsBack() {
        val labels = listOf(newLabel(id = "l1", name = "home"))
        var deleted: String? = null
        screen(
            NotesUiState(labels = labels),
            onDeleteLabel = { deleted = it },
        )
        openDrawer()
        compose.onNodeWithText("home").performTouchInput { longClick() }
        compose.onNodeWithText("Delete").performClick()
        assertEquals("l1", deleted)
    }
}
