package dev.cstraka.keeps.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateCheckTest {

    @Test
    fun parsesTagNameAndRejectsJunk() {
        assertEquals(
            "v0.4.0",
            parseLatestTag("""{"tag_name":"v0.4.0","draft":false}"""),
        )
        assertNull(parseLatestTag("""{"tag_name":""}"""))
        assertNull(parseLatestTag("""{"nope":true}"""))
        assertNull(parseLatestTag("not json"))
    }

    @Test
    fun newerMeansNumericallyGreater() {
        assertTrue(isNewer("v0.4.0", "0.3.0"))
        assertTrue(isNewer("v0.3.1", "0.3.0"))
        assertTrue(isNewer("v0.10.0", "0.9.9"))
        assertFalse(isNewer("v0.3.0", "0.3.0"))
        assertFalse(isNewer("v0.2.0", "0.3.0"))
        assertFalse(isNewer("0.3.0", "0.3.0-dev"))
    }

    @Test
    fun unparseableSidesFailClosed() {
        assertFalse(isNewer("", "0.3.0"))
        assertFalse(isNewer("v0.4.0", ""))
        assertFalse(isNewer("latest", "0.3.0"))
        assertFalse(isNewer("v0.4.0", "dev"))
    }
}
