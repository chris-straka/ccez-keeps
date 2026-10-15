package dev.cstraka.keeps.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.cstraka.keeps.update.UpdateUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Full-screen Settings: sections, inline rename, theme, updates, sign-out confirm. */
@RunWith(AndroidJUnit4::class)
class SettingsScreenUiTest {

    @get:Rule
    val compose = createComposeRule()

    private var renamed: String? = null
    private var theme: ThemeMode? = null
    private var signOuts = 0
    private var allowTaps = 0
    private var backs = 0

    private fun settings(
        message: String? = null,
        canInstall: Boolean = true,
        current: ThemeMode = ThemeMode.SYSTEM,
        fontScale: Float? = null,
    ) {
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(base.density, fontScale ?: base.fontScale),
            ) {
                SettingsScreen(
                    deviceName = "Pixel",
                    message = message,
                    busy = false,
                    onRename = { renamed = it },
                    onRotate = {},
                    theme = current,
                    onTheme = { theme = it },
                    appVersion = "0.6.0",
                    update = UpdateUi(),
                    canInstallUpdates = canInstall,
                    onCheckUpdate = {},
                    onAllowInstalls = { allowTaps++ },
                    onExport = {},
                    onImport = {},
                    onSignOut = { signOuts++ },
                    onBack = { backs++ },
                )
            }
        }
    }

    @Test
    fun showsEverySectionAndBackArrow() {
        settings()
        compose.onNodeWithText("Settings").assertIsDisplayed()
        for (header in listOf("This phone", "Appearance", "Backup", "Updates", "Account")) {
            compose.onNodeWithText(header).performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.runOnIdle { assertEquals(1, backs) }
    }

    @Test
    fun revokeNoteSitsOnTheLoginRow() {
        settings()
        compose.onNodeWithText("New login").assertIsDisplayed()
        compose.onNodeWithText(
            "Replaces this phone's token. Revoke it anytime from the web app device list.",
        ).assertIsDisplayed()
    }

    @Test
    fun renamesInline() {
        settings(message = "Renamed to “Work phone”.")
        // Unchanged name: nothing to save yet.
        compose.onNodeWithContentDescription("Rename").assertIsNotEnabled()
        compose.onNodeWithTag("device-name").performTextReplacement("Work phone")
        compose.onNodeWithContentDescription("Rename").performClick()
        compose.runOnIdle { assertEquals("Work phone", renamed) }
        compose.onNodeWithTag("settings-message").assertIsDisplayed()
    }

    @Test
    fun themeIsASegmentedChoice() {
        settings(current = ThemeMode.LIGHT)
        compose.onNodeWithTag("theme-light").assertIsSelected()
        compose.onNodeWithTag("theme-dark").performClick()
        compose.runOnIdle { assertEquals(ThemeMode.DARK, theme) }
    }

    @Test
    fun installPermissionRowAsksWhenMissing() {
        settings(canInstall = false)
        compose.onNodeWithText("Not allowed yet · tap to let Keeps install its own updates")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("install-permission").performClick()
        compose.runOnIdle { assertEquals(1, allowTaps) }
    }

    @Test
    fun installPermissionRowSaysWhatHappensNext() {
        settings(canInstall = true)
        compose.onNodeWithText("Allowed · new versions install when you leave the app")
            .performScrollTo().assertIsDisplayed()
    }

    @Test
    fun signOutNeedsConfirmation() {
        settings()
        compose.onNodeWithText("Sign out this phone").performScrollTo().performClick()
        compose.onNodeWithText("Sign out this phone?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(0, signOuts) }
        compose.onNodeWithText("Sign out this phone").performScrollTo().performClick()
        compose.onNodeWithTag("confirm-sign-out").performClick()
        compose.runOnIdle { assertEquals(1, signOuts) }
    }

    @Test
    fun rowsKeep48dpTargetsAtLargeFont() {
        settings(fontScale = 2f)
        for (tag in listOf("check-updates", "install-permission", "theme-system")) {
            compose.onNodeWithTag(tag).performScrollTo().assertHeightIsAtLeast(48.dp)
        }
        compose.onNodeWithText("Export notes").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Sign out this phone").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun largeFontThemeIsRadioRows() {
        settings(fontScale = 2f, current = ThemeMode.SYSTEM)
        compose.onNodeWithTag("theme-system").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("theme-light").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(ThemeMode.LIGHT, theme) }
    }

    @Test
    fun opensFromDrawerAndBackReturnsToNotes() {
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
                onSignOut = { signOuts++ },
                deleteError = false,
                onClearDeleteError = {},
            )
        }
        compose.onNodeWithContentDescription("Menu").performClick()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithTag("settings-screen").assertIsDisplayed()
        // The notes underneath are hidden from semantics while it is open.
        assertEquals(0, compose.onAllNodesWithContentDescription("Menu").fetchSemanticsNodes().size)
        compose.onNodeWithContentDescription("Back").performClick()
        assertEquals(0, compose.onAllNodesWithText("Check for updates").fetchSemanticsNodes().size)
        compose.onNodeWithContentDescription("Menu").assertIsDisplayed()
        assertNull(renamed)
    }
}
