package dev.cstraka.keeps.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IdeasLabelTest {
    @Test
    fun acceptsTheButtonsLabelAndHandMadeSpellings() {
        for (n in listOf("Ideas", "ideas", "Idea", " IDEAS ")) assertTrue(isIdeasLabelName(n), n)
        for (n in listOf("Idea list", "my ideas", "ide", "")) assertFalse(isIdeasLabelName(n), n)
    }

    @Test
    fun ideaNotesAreTheOnesCarryingALiveIdeasLabel() {
        val labels = listOf(
            Label(id = "a", name = "Ideas"),
            Label(id = "b", name = "idea"),
            Label(id = "c", name = "Work"),
            Label(id = "d", name = "Ideas", deleted = true),
        )
        val ids = ideasLabelIds(labels)
        assertEquals(setOf("a", "b"), ids)
        assertTrue(isIdeaNote(Note(id = "1", labelIds = listOf("c", "b")), ids))
        assertFalse(isIdeaNote(Note(id = "2", labelIds = listOf("c")), ids))
        assertFalse(isIdeaNote(Note(id = "3", labelIds = listOf("d")), ids))
        assertFalse(isIdeaNote(Note(id = "4"), ids))
    }
}
