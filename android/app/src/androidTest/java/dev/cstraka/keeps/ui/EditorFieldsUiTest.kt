package dev.cstraka.keeps.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The editor sheet on its own: no heading line, Enter in the title
 * moves to the body, the body's undo steps back over typing and the
 * format tools, and Save hands back what was typed.
 */
@RunWith(AndroidJUnit4::class)
class EditorFieldsUiTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun newNote_typesTitleAndBody_undoes_andSaves() {
        var saved: Pair<String, String>? = null
        compose.setContent {
            NoteDialog(
                title = "Take a note…",
                initialTitle = "",
                initialBody = "",
                initialColor = "default",
                onDismiss = {},
                onConfirm = { t, b, _, _, _, _, _, _ -> saved = t to b },
                onSaveDrawing = { _, _ -> },
            )
        }
        compose.onAllNodesWithText("Take a note…").assertCountEquals(0)
        compose.onNodeWithTag("editorTitle").performTextInput("Errands")
        compose.onNodeWithTag("editorTitle").performImeAction()
        compose.onNodeWithTag("editorBody").assertIsFocused()
        compose.onNodeWithTag("editorBody").performTextInput("milk")
        compose.onNodeWithContentDescription("Bold").performClick()
        compose.onNodeWithTag("editorBody").assertTextEquals("milk****")
        compose.onNodeWithContentDescription("Undo").performClick()
        compose.onNodeWithTag("editorBody").assertTextEquals("milk")
        // For a look at the sheet without its old heading line.
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        File(ctx.getExternalFilesDir(null), "editor.png").outputStream().use {
            compose.onNodeWithTag("editorCard").captureToImage().asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()
        assertEquals("Errands" to "milk", saved)
    }
}
