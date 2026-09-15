package dev.cstraka.keeps.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApiTest {

    @Test
    fun syncRequest_splitsUpsertsAndTombstones() {
        val dirty = listOf(
            newNote(id = "1", body = "live", updatedAt = 100L),
            newNote(id = "2", body = "gone", updatedAt = 200L, deleted = true),
        )
        val decoded: SyncRequest = apiJson.decodeFromString(encodeSyncRequest(dirty, since = 7L))
        assertEquals(listOf("1"), decoded.upserts.map { it.id })
        assertEquals(listOf("2"), decoded.tombstones.map { it.id })
        assertEquals(7L, decoded.since)
    }

    @Test
    fun syncRequest_usesFrozenCamelCaseKeys() {
        val body = encodeSyncRequest(listOf(newNote(id = "1")), since = 0L)
        assertTrue(body.contains("\"upserts\""))
        assertTrue(body.contains("\"tombstones\""))
        assertTrue(body.contains("\"updatedAt\""))
        assertTrue(body.contains("\"deviceId\"").not())
    }

    @Test
    fun pullAndSyncResponses_decodeWithDefaults() {
        val pull = decodePullResponse("""{"notes":[],"cursor":42}""")
        assertEquals(42L, pull.cursor)
        assertTrue(pull.notes.isEmpty())
        val push = decodeSyncResponse("""{"applied":1,"deltas":[],"cursor":43}""")
        assertEquals(1, push.applied)
        assertEquals(43L, push.cursor)
    }

    @Test
    fun note_roundTripsThroughJson() {
        val note = newNote(
            id = "abc", title = "t", body = "b", color = "blue",
            pinned = true, archived = true, updatedAt = 123L, deleted = true,
        )
        val decoded: Note = apiJson.decodeFromString(apiJson.encodeToString(Note.serializer(), note))
        assertEquals(note, decoded)
    }

    @Test
    fun deviceRenameAndRotate_useFrozenKeys() {
        val rename: RenameRequest = apiJson.decodeFromString(
            apiJson.encodeToString(RenameRequest.serializer(), RenameRequest("d1", "pixel")),
        )
        assertEquals("d1", rename.deviceId)
        assertEquals("pixel", rename.deviceName)
        val raw = apiJson.encodeToString(RenameRequest.serializer(), RenameRequest("d1", "pixel"))
        assertTrue(raw.contains("\"deviceId\""))
        assertTrue(raw.contains("\"deviceName\""))
        val rotate: RotateRequest = apiJson.decodeFromString(
            apiJson.encodeToString(RotateRequest.serializer(), RotateRequest("d1")),
        )
        assertEquals("d1", rotate.deviceId)
        val enroll: EnrollResponse = apiJson.decodeFromString("""{"deviceId":"d1","token":"t"}""")
        assertEquals("d1", enroll.deviceId)
        assertEquals("t", enroll.token)
    }
}
