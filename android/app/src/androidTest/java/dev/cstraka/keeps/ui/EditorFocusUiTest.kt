package dev.cstraka.keeps.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.cstraka.keeps.KeepsApp
import dev.cstraka.keeps.MainActivity
import dev.cstraka.keeps.sync.Note
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Owner report (2026-10-22): Enter in a new note's title should move to
 * the body, and tapping a note's body should open it with the caret in
 * the body, not the title. Same enrolled-device setup as EditSaveUiTest.
 */
@RunWith(AndroidJUnit4::class)
class EditorFocusUiTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val app: KeepsApp get() = ApplicationProvider.getApplicationContext()
    private lateinit var seeded: Note

    @Before
    fun seed() {
        app.authStore.saveEnrollment("test-device", "test-token", "emulator")
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(app.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        }
        runBlocking {
            for (n in app.localStore.all()) app.localStore.dropLocal(n.id)
            seeded = app.localStore.create("Groceries", "milk")
        }
    }

    @After
    fun cleanup() {
        runBlocking { for (n in app.localStore.all()) app.localStore.dropLocal(n.id) }
        app.authStore.clear()
    }

    private fun waitForSeed() {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("milk").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun enterInNewNoteTitle_movesToBody() {
        ActivityScenario.launch(MainActivity::class.java).use {
            waitForSeed()
            compose.onNodeWithContentDescription("Take a note").performClick()
            compose.onNodeWithTag("editorTitle").assertIsFocused()
            compose.onNodeWithTag("editorTitle").performTextInput("Errands")
            compose.onNodeWithTag("editorTitle").performImeAction()
            compose.onNodeWithTag("editorBody").assertIsFocused()
        }
    }

    @Test
    fun tappingNoteBody_opensWithBodyFocused() {
        ActivityScenario.launch(MainActivity::class.java).use {
            waitForSeed()
            compose.onNodeWithText("milk").performClick()
            compose.onNodeWithTag("editorCard").assertIsDisplayed()
            compose.onNodeWithTag("editorBody").assertIsFocused()
        }
    }

    @Test
    fun tappingNoteTitle_opensWithTitleFocused() {
        ActivityScenario.launch(MainActivity::class.java).use {
            waitForSeed()
            compose.onNodeWithText("Groceries").performClick()
            compose.onNodeWithTag("editorCard").assertIsDisplayed()
            compose.onNodeWithTag("editorTitle").assertIsFocused()
        }
    }
}
