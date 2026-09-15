package dev.cstraka.keeps.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class LabelColorsTest {

    @Test
    fun knownKeys_matchTheWebPalette() {
        assertEquals(0xFFF28B82L, labelTint("red", dark = true))
        assertEquals(0xFF1A73E8L, labelTint("blue", dark = false))
    }

    @Test
    fun themesUseDistinctPalettes() {
        for (key in listOf("red", "orange", "yellow", "green", "teal", "blue", "purple", "pink")) {
            assertNotEquals(labelTint(key, dark = true), labelTint(key, dark = false), key)
        }
    }

    @Test
    fun unknownKey_fallsBackToNeutral() {
        assertEquals(labelTint("default", dark = true), labelTint("nope", dark = true))
        assertEquals(labelTint("default", dark = false), labelTint("nope", dark = false))
    }
}
