package dev.cstraka.keeps.ui

import android.graphics.Bitmap
import android.util.Base64
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.cstraka.keeps.data.processImageBytes
import dev.cstraka.keeps.sync.Attachment
import dev.cstraka.keeps.sync.ChecklistItem
import dev.cstraka.keeps.sync.Note
import dev.cstraka.keeps.sync.isAttachment
import java.io.ByteArrayOutputStream
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device verification for checklists + image attachments: cards show
 * item rows and thumbnail previews, the editor round-trips a toggle into
 * a saved checklist with a body fallback, and the downscaler mints valid
 * attachments from real photo-sized bitmaps.
 */
@RunWith(AndroidJUnit4::class)
class ChecklistAttachmentsUiTest {

    @get:Rule
    val compose = createComposeRule()

    private fun pngDataUrl(size: Int, color: Int): String {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(color)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        return "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private val attachment = Attachment(
        id = "a1",
        name = "beach.png",
        mime = "image/png",
        size = 100L,
        dataUrl = pngDataUrl(4, 0xFFFF0000.toInt()),
        thumbUrl = pngDataUrl(2, 0xFF00FF00.toInt()),
    )

    private fun items(n: Int) = (0 until n).map {
        ChecklistItem(id = "c$it", text = "item $it", checked = it == 0)
    }

    @Test
    fun card_rendersChecklistPreviewAndThumbInsteadOfBody() {
        val note = Note(
            id = "n1", title = "Trip", body = "fallback",
            checklist = items(7), attachments = listOf(attachment),
        )
        compose.setContent {
            NotesScreen(
                state = NotesUiState(notes = listOf(note)),
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
            )
        }
        compose.onNodeWithText("item 0").assertIsDisplayed()
        compose.onNodeWithText("+2 more").assertIsDisplayed()
        compose.onAllNodesWithText("fallback").assertCountEquals(0)
        compose.onNodeWithContentDescription("beach.png").assertIsDisplayed()
    }

    @Test
    fun editor_togglesItemRenamesAndSavesWithBodyFallback() {
        var savedChecklist: List<ChecklistItem>? = null
        var savedBody: String? = null
        var savedAttachments: List<Attachment>? = null
        compose.setContent {
            NoteDialog(
                title = "Edit note",
                initialTitle = "Shop",
                initialBody = "milk\neggs",
                initialColor = "default",
                initialChecklist = listOf(
                    ChecklistItem(id = "c1", text = "milk", checked = false),
                    ChecklistItem(id = "c2", text = "eggs", checked = false),
                ),
                initialAttachments = listOf(attachment),
                onDismiss = {},
                onConfirm = { _, b, _, _, _, _, checklist, attachments ->
                    savedBody = b
                    savedChecklist = checklist
                    savedAttachments = attachments
                },
                onSaveDrawing = { _, _ -> },
            )
        }
        // Checklist rows render instead of the body field.
        compose.onNodeWithText("milk").assertIsDisplayed()
        compose.onAllNodesWithText("milk\neggs").assertCountEquals(0)
        // Toggle the first item, rename the second, save.
        compose.onAllNodes(isToggleable())[0].performClick()
        compose.onNodeWithText("eggs").performTextInput(" (free-range)")
        compose.onNodeWithText("Save").performClick()

        val got = savedChecklist ?: throw AssertionError("checklist was not saved")
        assert(got.size == 2) { "expected 2 items, got ${got.size}" }
        assert(got[0].checked) { "first item should be checked" }
        assert(got[1].text == "eggs (free-range)") { "rename lost: ${got[1].text}" }
        assert(savedBody == "milk\neggs (free-range)") { "body fallback wrong: $savedBody" }
        assert(savedAttachments?.map { it.id } == listOf("a1")) { "attachments lost" }
    }

    @Test
    fun processImageBytes_downscalesPhotoToValidAttachment() {
        val bmp = Bitmap.createBitmap(2000, 1000, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(0xFF0000FF.toInt())
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        val got = processImageBytes(out.toByteArray(), "sky.png")
            ?: throw AssertionError("photo-sized bitmap was rejected")
        assert(isAttachment(got)) { "downscaled output fails validation" }
        assert(got.mime == "image/jpeg") { "expected jpeg, got ${got.mime}" }
        assert(got.name == "sky.png") { "name lost: ${got.name}" }
    }

    @Test
    fun processImageBytes_rejectsNonImages() {
        assert(processImageBytes("not an image".toByteArray(), "x.bin") == null) {
            "junk bytes must not become an attachment"
        }
    }
}
