package dev.cstraka.keeps.ui

import android.graphics.Bitmap
import android.util.Base64
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cstraka.keeps.sync.Attachment
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The editor's footer: an attached photo shows right above the tools
 * (not only at the top of a long note), the note tools share the bar
 * with Cancel and Save, a long press names an icon, and typing at the
 * end of a long note keeps the caret above the tools.
 */
@RunWith(AndroidJUnit4::class)
class EditorBarUiTest {

    @get:Rule
    val compose = createComposeRule()

    private fun pngDataUrl(size: Int, color: Int): String {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(color)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        return "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private val longBody = (1..60).joinToString("\n") { "line $it of a long note" }

    private fun shot(name: String) {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        File(ctx.getExternalFilesDir(null), name).outputStream().use {
            compose.onNodeWithTag("editorCard").captureToImage().asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    // The keyboard may still be sliding in (the sheet shrinks above it):
    // wait until the bar stops moving before measuring.
    private fun settle() {
        var last = -1f
        compose.waitUntil(5_000) {
            Thread.sleep(150)
            val top = compose.onNodeWithTag("editorBar").fetchSemanticsNode().boundsInRoot.top
            (top == last).also { last = top }
        }
    }

    @Test
    fun photoShowsAboveTheBar_inALongNote() {
        val photo = Attachment(
            id = "a1", name = "beach.png", mime = "image/png", size = 100L,
            dataUrl = pngDataUrl(4, 0xFF3F7FBF.toInt()),
            thumbUrl = pngDataUrl(2, 0xFF3F7FBF.toInt()),
        )
        compose.setContent {
            NoteDialog(
                title = "Note",
                initialTitle = "Trip",
                initialBody = longBody,
                initialColor = "default",
                initialAttachments = listOf(photo),
                onDismiss = {},
                onConfirm = { _, _, _, _, _, _, _, _ -> },
                onSaveDrawing = { _, _ -> },
            )
        }
        compose.onNodeWithContentDescription("beach.png").assertIsDisplayed()
        settle()
        val strip = compose.onNodeWithTag("photoStrip").fetchSemanticsNode().boundsInRoot
        val bar = compose.onNodeWithTag("editorBar").fetchSemanticsNode().boundsInRoot
        assert(strip.bottom <= bar.top) { "photos $strip not above the bar $bar" }
        // Note tools and Save share one row.
        val photoTool = compose.onNodeWithContentDescription("Add photo").fetchSemanticsNode().boundsInRoot
        val save = compose.onNodeWithText("Save").fetchSemanticsNode().boundsInRoot
        assert(photoTool.center.y in save.top..save.bottom) { "tools $photoTool not beside Save $save" }
        shot("editor-bar.png")
        compose.onNodeWithContentDescription("Colour").performTouchInput { longClick() }
        compose.onNodeWithText("Colour").assertIsDisplayed()
        shot("editor-bar-hold.png")
    }

    @Test
    fun typingAtTheEndOfALongNote_staysInView() {
        compose.setContent {
            NoteDialog(
                title = "Note",
                initialTitle = "Long",
                initialBody = longBody,
                initialColor = "default",
                startInBody = true,
                onDismiss = {},
                onConfirm = { _, _, _, _, _, _, _, _ -> },
                onSaveDrawing = { _, _ -> },
            )
        }
        compose.onNodeWithTag("editorBody").performTextInput("\nnew words at the end")
        settle()
        // Unclipped: boundsInRoot would cut the body off at the scroll edge.
        val node = compose.onNodeWithTag("editorBody").fetchSemanticsNode()
        val bodyBottom = node.positionInRoot.y + node.size.height
        val tools = compose.onNodeWithTag("textTools").fetchSemanticsNode().boundsInRoot
        // The caret is on the body's last line: its bottom must be scrolled
        // up to the tools, not left below them.
        assert(bodyBottom <= tools.top + 2f) { "end of note $bodyBottom hidden under tools at ${tools.top}" }
        shot("editor-long.png")
    }
}
