package dev.cstraka.keeps.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImportTest {

    @Test
    fun mergeImport_newIdsAreAdded() {
        val merged = mergeImportNotes(
            listOf(newNote(id = "a", title = "local", updatedAt = 10L)),
            listOf(newNote(id = "b", title = "backup", updatedAt = 5L)),
        )
        assertEquals(setOf("a", "b"), merged.map { it.id }.toSet())
    }

    @Test
    fun mergeImport_lwwPerIdEitherSide() {
        val local = listOf(newNote(id = "n", title = "mine", updatedAt = 200L))
        val backup = listOf(newNote(id = "n", title = "stale", updatedAt = 100L))
        // A stale backup never clobbers a newer local edit, either side.
        assertEquals("mine", mergeImportNotes(local, backup).single().title)
        assertEquals("mine", mergeImportNotes(backup, local).single().title)
    }

    @Test
    fun mergeImport_backupTombstoneBeatsOlderLiveNote() {
        val local = listOf(newNote(id = "n", title = "live", updatedAt = 100L))
        val incoming = listOf(newNote(id = "n", title = "live", updatedAt = 200L, deleted = true))
        assertTrue(mergeImportNotes(local, incoming).single().deleted)
    }

    @Test
    fun mergeImport_dropsInvalidRowsKeepsValid() {
        val merged = mergeImportNotes(
            emptyList(),
            listOf(
                newNote(id = "ok", title = "fine", updatedAt = 1L),
                // 21 label ids fails isNote: corrupt row, must not sink the file.
                newNote(id = "bad", updatedAt = 1L, labelIds = List(21) { "l$it" }),
            ),
        )
        assertEquals(listOf("ok"), merged.map { it.id })
    }

    @Test
    fun mergeImport_noDuplicatesNoMutation() {
        val local = listOf(newNote(id = "n", title = "t", updatedAt = 50L))
        val merged = mergeImportNotes(local, local.map { it.copy() })
        assertEquals(1, merged.size)
        assertEquals(50L, local.single().updatedAt)
    }

    @Test
    fun mergeImport_parsedBackupFileMerges() {
        val backup = exportNotes(listOf(newNote(id = "w", title = "web", updatedAt = 5L)))
        val merged = mergeImportNotes(emptyList(), parseExport(backup).notes)
        assertEquals("web", merged.single().title)
    }
}
