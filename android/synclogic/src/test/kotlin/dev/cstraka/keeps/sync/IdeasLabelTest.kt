package dev.cstraka.keeps.sync

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IdeasLabelTest {
    @Test
    fun acceptsTheButtonsLabelAndHandMadeSpellings() {
        for (n in listOf("Ideas", "ideas", "Idea", " IDEAS ")) assertTrue(isIdeasLabelName(n), n)
        for (n in listOf("Idea list", "my ideas", "ide", "")) assertFalse(isIdeasLabelName(n), n)
    }
}
