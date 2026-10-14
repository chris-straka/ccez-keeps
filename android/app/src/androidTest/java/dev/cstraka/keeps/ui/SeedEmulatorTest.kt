package dev.cstraka.keeps.ui

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cstraka.keeps.KeepsApp
import dev.cstraka.keeps.sync.ChecklistItem
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Not a test: fills the emulator's app data with a fake enrollment and a
 * batch of varied notes so the app can be driven by hand for screenshots
 * and scroll repros. Skipped unless run with `-Pandroid.testInstrumentationRunnerArguments.seed=1`
 * (and a build whose KEEPS_BASE_URL is unreachable, so sync never touches prod).
 */
@RunWith(AndroidJUnit4::class)
class SeedEmulatorTest {

    private val app: KeepsApp get() = ApplicationProvider.getApplicationContext()

    @Test
    fun seedNotes() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("seed") == "1")
        app.authStore.saveEnrollment("test-device", "test-token", "emulator")
        runBlocking {
            for (n in app.localStore.all()) app.localStore.dropLocal(n.id)
            val lorem = "Pick up the parcel from the depot before five, then drop the " +
                "library books on the way back. Check whether the hardware store has " +
                "the M4 bolts in stock."
            val colors = listOf("default", "red", "orange", "yellow", "green", "teal", "blue", "purple", "pink")
            for (i in 1..40) {
                val body = when (i % 4) {
                    0 -> lorem
                    1 -> "short note $i"
                    2 -> lorem.take(60 + (i * 7) % 90)
                    else -> "line one\nline two\nline three $i"
                }
                val checklist = if (i % 7 == 0) listOf(
                    ChecklistItem(id = "c$i-1", text = "milk", checked = true),
                    ChecklistItem(id = "c$i-2", text = "eggs", checked = false),
                    ChecklistItem(id = "c$i-3", text = "bread", checked = false),
                ) else null
                val note = app.localStore.create(
                    title = if (i % 3 == 0) "" else "Note $i",
                    body = body,
                    checklist = checklist,
                )
                app.localStore.put(note.copy(color = colors[i % colors.size], updatedAt = note.updatedAt - i * 60_000L))
            }
        }
    }
}
