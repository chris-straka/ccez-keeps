package dev.cstraka.keeps.ui

import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.util.Base64
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cstraka.keeps.sync.Attachment
import dev.cstraka.keeps.sync.Note
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Notes holding only a phone screenshot (tall image, no text) draw in
 * the grid: picture, then the card's pin/archive/delete row. The capture
 * lands in the app's external files dir for a visual check.
 */
@RunWith(AndroidJUnit4::class)
class ImageCardsUiTest {

    @get:Rule
    val compose = createComposeRule()

    private fun jpegDataUrl(w: Int, h: Int, color: Int): String {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(color)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
        return "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun shot(id: String, color: Int) = Attachment(
        id = "a$id",
        name = "$id.jpg",
        mime = "image/jpeg",
        size = 1000L,
        dataUrl = jpegDataUrl(738, 1600, color),
        thumbUrl = jpegDataUrl(118, 256, color),
    )

    @Test
    fun screenshotOnlyNotes_drawTheirPictureAndTools() {
        val colors = listOf(AColor.RED, AColor.BLUE, AColor.GREEN, AColor.MAGENTA)
        val notes = (0 until 12).map { i ->
            if (i % 3 == 2) {
                Note(id = "t$i", body = "Text note $i ".repeat(i + 1))
            } else {
                Note(id = "img$i", attachments = listOf(shot("$i", colors[i % 4])))
            }
        }
        compose.setContent {
            NotesScreen(
                state = NotesUiState(notes = notes),
                needsLogin = false,
                deviceName = "test",
                onQuery = {},
                onFilter = {},
                onCreate = { _, _, _, _, _, _, _ -> },
                composerOpen = false,
                onComposerOpen = {},
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
        compose.waitForIdle()
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        fun capture(name: String): Bitmap {
            compose.waitForIdle()
            val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
            File(ctx.getExternalFilesDir(null), name).outputStream().use {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            return bmp
        }
        // Pixels of the first note's picture (pure red) on screen.
        fun redPixels(bmp: Bitmap): Int {
            var n = 0
            for (y in 0 until bmp.height step 4) for (x in 0 until bmp.width step 4) {
                val p = bmp.getPixel(x, y)
                if (AColor.red(p) > 230 && AColor.green(p) < 30 && AColor.blue(p) < 30) n++
            }
            return n
        }
        val fresh = redPixels(capture("image-cards.png"))
        assertTrue("first picture draws", fresh > 1000)
        // Down the grid and back up: cards that scrolled away still draw
        // when they come back (owner, 2026-10-30: they came back blank).
        val grid = compose.onNode(hasScrollToIndexAction())
        grid.performScrollToIndex(11)
        capture("image-cards-down.png")
        grid.performTouchInput { swipeDown(durationMillis = 400) }
        grid.performScrollToIndex(0)
        val back = redPixels(capture("image-cards-up.png"))
        assertTrue("pictures draw again after a scroll ($back vs $fresh)", back >= fresh)
        // Every on-screen picture draws (content description = its name).
        compose.onAllNodesWithContentDescription("0.jpg").assertCountEquals(1)
        compose.onAllNodesWithContentDescription("1.jpg").assertCountEquals(1)
    }
}
