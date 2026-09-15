package dev.cstraka.keeps.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * API DTOs. JSON keys match contracts/api.md exactly (camelCase); the
 * client treats server `cursor`/`since` values as opaque numbers.
 */
@Serializable
data class PullResponse(val notes: List<Note> = emptyList(), val cursor: Long = 0L)

@Serializable
data class SyncRequest(
    val upserts: List<Note> = emptyList(),
    val tombstones: List<Note> = emptyList(),
    val since: Long = 0L,
)

@Serializable
data class SyncResponse(
    val applied: Int = 0,
    val deltas: List<Note> = emptyList(),
    val cursor: Long = 0L,
)

@Serializable
data class EnrollResponse(val deviceId: String, val token: String)

@Serializable
data class ExchangeRequest(val code: String, val deviceName: String = "")

@Serializable
data class DeviceInfo(
    val id: String,
    val deviceName: String = "",
    val createdAt: Long = 0L,
    val lastSeenAt: Long = 0L,
    val revoked: Boolean = false,
)

@Serializable
data class DeviceListResponse(val devices: List<DeviceInfo> = emptyList())

@Serializable
data class RenameRequest(val deviceId: String, val deviceName: String = "")

@Serializable
data class RenameResponse(val renamed: Boolean = false)

@Serializable
data class RotateRequest(val deviceId: String)

val apiJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

fun encodeSyncRequest(dirty: List<Note>, since: Long): String =
    apiJson.encodeToString(
        SyncRequest(
            upserts = dirty.filter { !it.deleted },
            tombstones = dirty.filter { it.deleted },
            since = since,
        ),
    )

fun decodeSyncResponse(body: String): SyncResponse = apiJson.decodeFromString(body)

fun decodePullResponse(body: String): PullResponse = apiJson.decodeFromString(body)

/** Drawings lane DTOs: same shapes as the notes lane, own seq cursor. */
@Serializable
data class DrawPullResponse(val drawings: List<Drawing> = emptyList(), val cursor: Long = 0L)

@Serializable
data class DrawSyncRequest(
    val upserts: List<Drawing> = emptyList(),
    val tombstones: List<Drawing> = emptyList(),
    val since: Long = 0L,
)

@Serializable
data class DrawSyncResponse(
    val applied: Int = 0,
    val deltas: List<Drawing> = emptyList(),
    val cursor: Long = 0L,
)

fun encodeDrawSyncRequest(dirty: List<Drawing>, since: Long): String =
    apiJson.encodeToString(
        DrawSyncRequest(
            upserts = dirty.filter { !it.deleted },
            tombstones = dirty.filter { it.deleted },
            since = since,
        ),
    )

fun decodeDrawSyncResponse(body: String): DrawSyncResponse = apiJson.decodeFromString(body)

fun decodeDrawPullResponse(body: String): DrawPullResponse = apiJson.decodeFromString(body)

/** Labels lane DTOs: same shapes as the notes lane, own seq cursor. */
@Serializable
data class LabelPullResponse(val labels: List<Label> = emptyList(), val cursor: Long = 0L)

@Serializable
data class LabelSyncRequest(
    val upserts: List<Label> = emptyList(),
    val tombstones: List<Label> = emptyList(),
    val since: Long = 0L,
)

@Serializable
data class LabelSyncResponse(
    val applied: Int = 0,
    val deltas: List<Label> = emptyList(),
    val cursor: Long = 0L,
)

fun encodeLabelSyncRequest(dirty: List<Label>, since: Long): String =
    apiJson.encodeToString(
        LabelSyncRequest(
            upserts = dirty.filter { !it.deleted },
            tombstones = dirty.filter { it.deleted },
            since = since,
        ),
    )

fun decodeLabelSyncResponse(body: String): LabelSyncResponse = apiJson.decodeFromString(body)

fun decodeLabelPullResponse(body: String): LabelPullResponse = apiJson.decodeFromString(body)
