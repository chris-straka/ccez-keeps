package dev.cstraka.keeps.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.cstraka.keeps.update.UpdatePhase
import dev.cstraka.keeps.update.UpdateUi
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Settings' "Check for updates" row: version, last check, updater state. */
@RunWith(AndroidJUnit4::class)
class UpdateRowUiTest {

    @get:Rule
    val compose = createComposeRule()

    private fun settings(update: UpdateUi, onCheck: () -> Unit = {}) {
        compose.setContent {
            NotesScreen(
                state = NotesUiState(),
                needsLogin = false,
                deviceName = "test",
                onQuery = {},
                onFilter = {},
                onCreate = { _, _, _, _, _, _, _ -> },
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
                appVersion = "0.5.3",
                update = update,
                onCheckUpdate = onCheck,
            )
        }
        compose.onNodeWithContentDescription("Menu").performClick()
        compose.onNodeWithText("Settings").performClick()
    }

    @Test
    fun showsVersionAndNeverChecked() {
        settings(UpdateUi())
        compose.onNodeWithText("Check for updates").assertIsDisplayed()
        compose.onNodeWithText("Keeps 0.5.3 · not checked yet").assertIsDisplayed()
    }

    @Test
    fun showsLastCheckAndUpToDate() {
        settings(UpdateUi(UpdatePhase.UP_TO_DATE, lastCheck = System.currentTimeMillis() - 5 * 60_000L))
        compose.onNodeWithText("Keeps 0.5.3 · checked 5 minutes ago").assertIsDisplayed()
        compose.onNodeWithText("Up to date").assertIsDisplayed()
    }

    @Test
    fun readyUpdateSaysWhatHappensNext() {
        settings(UpdateUi(UpdatePhase.WAITING_FOR_YOU, tag = "v0.6.0", lastCheck = 1L))
        compose.onNodeWithText("v0.6.0 ready · tap to install").assertIsDisplayed()
    }

    @Test
    fun tapChecksNow() {
        var taps = 0
        settings(UpdateUi(UpdatePhase.NEEDS_PERMISSION, tag = "v0.6.0")) { taps++ }
        compose.onNodeWithText("v0.6.0 ready · tap to allow Keeps to install it").assertIsDisplayed()
        compose.onNodeWithTag("check-updates").performClick()
        compose.runOnIdle { assertEquals(1, taps) }
    }
}
