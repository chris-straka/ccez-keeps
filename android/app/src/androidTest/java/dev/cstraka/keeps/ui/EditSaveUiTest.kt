package dev.cstraka.keeps.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.cstraka.keeps.KeepsApp
import dev.cstraka.keeps.MainActivity
import dev.cstraka.keeps.sync.Note
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Owner report (2026-10-13): editing a note on the phone and tapping Save
 * lost the edit. This drives the real activity, view model and Room store
 * (not a NoteDialog in isolation), so the whole save path is under test.
 *
 * The device is enrolled with a dummy token so the notes screen shows;
 * build with `KEEPS_BASE_URL=http://10.0.2.2:9` so the sync lane fails
 * fast as a connection error instead of hitting production.
 */
@RunWith(AndroidJUnit4::class)
class EditSaveUiTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val app: KeepsApp get() = ApplicationProvider.getApplicationContext()
    private lateinit var seeded: Note

    @Before
    fun seed() {
        app.authStore.saveEnrollment("test-device", "test-token", "emulator")
        runBlocking {
            for (n in app.localStore.all()) app.localStore.dropLocal(n.id)
            seeded = app.localStore.create("Groceries", "milk")
        }
    }

    @After
    fun cleanup() {
        runBlocking { app.localStore.dropLocal(seeded.id) }
        app.authStore.clear()
    }

    private fun bodyNow(): String? = runBlocking { app.localStore.noteById(seeded.id)?.body }
    private fun titleNow(): String? = runBlocking { app.localStore.noteById(seeded.id)?.title }

    @Test
    fun editingTitleAndBodyThenSave_persistsBoth() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("milk").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("milk").performClick()
            compose.onNodeWithText("Edit note").assertIsDisplayed()

            compose.onNodeWithTag("editorTitle").performTextClearance()
            compose.onNodeWithTag("editorTitle").performTextInput("Groceries (Sat)")
            compose.onNodeWithTag("editorBody").performTextClearance()
            compose.onNodeWithTag("editorBody").performTextInput("milk and eggs")
            compose.onNodeWithText("Save").performClick()

            // Give every launched write a chance to land, then look at Room.
            // (A timeout here reads as a plain value mismatch below.)
            try {
                compose.waitUntil(5_000) {
                    bodyNow() == "milk and eggs" && titleNow() == "Groceries (Sat)"
                }
            } catch (_: androidx.compose.ui.test.ComposeTimeoutException) {
            }
            Thread.sleep(1_500)
            assertEquals("milk and eggs", bodyNow())
            assertEquals("Groceries (Sat)", titleNow())
        }
    }
}

