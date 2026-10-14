package dev.cstraka.keeps.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.cstraka.keeps.sync.Note
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A note whose colour changes in state repaints its card at once. */
@RunWith(AndroidJUnit4::class)
class CardColorUiTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun card_repaintsWhenNoteColorChanges() {
        var color by mutableStateOf("red")
        var body by mutableStateOf("me")
        compose.setContent {
            NotesScreen(
                state = NotesUiState(notes = listOf(Note(id = "n1", title = "Paint", body = body, color = color))),
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
                theme = ThemeMode.LIGHT,
            )
        }
        fun cardPixel(): Int {
            // The card is the clickable ancestor of the title; sample its
            // right padding strip, clear of the rounded corners and text.
            val card = compose.onNode(hasClickAction() and hasText("Paint"))
            val img = card.captureToImage()
            return img.toPixelMap()[img.width - 10, img.height / 2].toArgb()
        }
        assertEquals(noteColor("red", dark = false).toArgb(), cardPixel())
        color = "teal"
        body = "repainted"
        compose.waitForIdle()
        Thread.sleep(500)
        compose.waitForIdle()
        compose.onNodeWithText("repainted").assertExists()
        assertEquals(noteColor("teal", dark = false).toArgb(), cardPixel())
    }
}
