package dev.cstraka.keeps.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RepeatTest {

    @Test
    fun nextRepeat_stepsByRule() {
        val firedAt = 1_000_000L
        assertEquals(firedAt + 24 * 60 * 60 * 1000L, nextRepeat(firedAt, "daily"))
        assertEquals(firedAt + 7 * 24 * 60 * 60 * 1000L, nextRepeat(firedAt, "weekly"))
    }

    @Test
    fun validation_acceptsKnownRulesAndNull() {
        assertTrue(isNote(newNote(id = "1", repeat = null)))
        assertTrue(isNote(newNote(id = "1", repeat = "daily")))
        assertTrue(isNote(newNote(id = "1", repeat = "weekly")))
        assertFalse(isNote(newNote(id = "1", repeat = "monthly")))
    }

    @Test
    fun repeat_roundTripsThroughJson() {
        val decoded: Note = apiJson.decodeFromString(
            apiJson.encodeToString(Note.serializer(), newNote(id = "1", repeat = "daily")),
        )
        assertEquals("daily", decoded.repeat)
        // Pre-repeat rows omit the key; the default keeps them firing once.
        val legacy: Note = apiJson.decodeFromString("""{"id":"2"}""")
        assertEquals(null, legacy.repeat)
    }
}
