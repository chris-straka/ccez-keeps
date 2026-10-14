package dev.cstraka.keeps.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.cstraka.keeps.sync.ChecklistItem
import dev.cstraka.keeps.sync.Note
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Owner report (2026-10-13): scrolling the note grid up and back down
 * left a single card rendered. Drives the real staggered grid with 60
 * notes of mixed heights through flings both ways and checks that a
 * full screen of cards is still laid out at every stop.
 */
@RunWith(AndroidJUnit4::class)
class GridScrollUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val lorem = "Pick up the parcel from the depot before five, then drop the " +
        "library books on the way back. Check whether the hardware store has the M4 bolts."

    private fun notes(): List<Note> = (1..60).map { i ->
        Note(
            id = "n$i",
            title = if (i % 3 == 0) "" else "Note $i",
            body = when (i % 4) {
                0 -> lorem
                1 -> "short $i"
                2 -> lorem.take(40 + (i * 7) % 100)
                else -> "one\ntwo\nthree $i"
            },
            color = listOf("default", "red", "yellow", "teal", "purple")[i % 5],
            checklist = if (i % 7 == 0) listOf(
                ChecklistItem(id = "c$i", text = "milk", checked = true),
                ChecklistItem(id = "d$i", text = "eggs"),
            ) else null,
            order = i.toDouble(),
        )
    }

    private fun renderedCards(): Int =
        compose.onAllNodesWithContentDescription("Pin").fetchSemanticsNodes().size

    @Test
    fun grid_keepsAScreenOfCardsAfterScrollingUpAndBack() {
        compose.setContent {
            NotesScreen(
                state = NotesUiState(notes = notes()),
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
            )
        }
        compose.onNodeWithText("Note 1").assertExists()
        val atTop = renderedCards()
        assertTrue("expected a screen of cards at the top, got $atTop", atTop >= 3)
        repeat(4) {
            compose.onRoot().performTouchInput { swipeUp(startY = bottom * 0.85f, endY = centerY * 0.6f, durationMillis = 80) }
            compose.waitForIdle()
            val n = renderedCards()
            assertTrue("after fling forward #$it only $n cards rendered", n >= 3)
        }
        repeat(6) {
            compose.onRoot().performTouchInput { swipeDown(startY = centerY * 0.6f, endY = bottom * 0.85f, durationMillis = 80) }
            compose.waitForIdle()
            val n = renderedCards()
            assertTrue("after fling back #$it only $n cards rendered", n >= 3)
        }
        compose.onNodeWithText("Note 1").assertExists()
        assertTrue("back at the top only ${renderedCards()} cards rendered", renderedCards() >= 3)
    }
}
