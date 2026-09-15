package dev.cstraka.keeps.sync

import dev.cstraka.keeps.auth.TokenProvider
import dev.cstraka.keeps.sync.ApiException
import dev.cstraka.keeps.sync.EnrollResponse
import dev.cstraka.keeps.sync.ExchangeRequest
import dev.cstraka.keeps.sync.PullResponse
import dev.cstraka.keeps.sync.SyncRequest
import dev.cstraka.keeps.sync.SyncResponse
import dev.cstraka.keeps.sync.DrawSyncRequest
import dev.cstraka.keeps.sync.LabelSyncRequest
import dev.cstraka.keeps.sync.apiJson
import dev.cstraka.keeps.sync.decodeDrawPullResponse
import dev.cstraka.keeps.sync.decodeDrawSyncResponse
import dev.cstraka.keeps.sync.decodeLabelPullResponse
import dev.cstraka.keeps.sync.decodeLabelSyncResponse
import dev.cstraka.keeps.sync.decodePullResponse
import dev.cstraka.keeps.sync.decodeSyncResponse
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class ApiException(val status: Int, message: String) : Exception("api $status: $message")

private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

/**
 * Thin OkHttp client over the frozen contract (contracts/api.md). Every
 * sync call carries the device Bearer token; exchange is the one call that
 * runs pre-auth (the single-use code is the credential).
 */
class KeepsApi(
    private val baseUrl: String,
    private val http: OkHttpClient,
    private val tokens: TokenProvider,
) {
    private fun authed(url: String): Request.Builder {
        val builder = Request.Builder().url(url)
        tokens.token()?.let { builder.header("Authorization", "Bearer $it") }
        return builder
    }

    private fun execute(request: Request): String {
        http.newCall(request).execute().use { res ->
            val body = res.body?.string() ?: ""
            if (!res.isSuccessful) throw ApiException(res.code, body.take(200))
            return body
        }
    }

    fun pull(cursor: Long): PullResponse {
        val req = authed("$baseUrl/api/notes?since=$cursor").get().build()
        return decodePullResponse(execute(req))
    }

    fun push(dirty: List<dev.cstraka.keeps.sync.Note>, since: Long): SyncResponse {
        val payload = SyncRequest(
            upserts = dirty.filter { !it.deleted },
            tombstones = dirty.filter { it.deleted },
            since = since,
        )
        val req = authed("$baseUrl/api/notes/sync")
            .post(apiJson.encodeToString(payload).toRequestBody(JSON_MEDIA))
            .build()
        return decodeSyncResponse(execute(req))
    }

    fun pullDrawings(cursor: Long): dev.cstraka.keeps.sync.DrawPullResponse {
        val req = authed("$baseUrl/api/drawings?since=$cursor").get().build()
        return decodeDrawPullResponse(execute(req))
    }

    fun pushDrawings(dirty: List<dev.cstraka.keeps.sync.Drawing>, since: Long): dev.cstraka.keeps.sync.DrawSyncResponse {
        val payload = DrawSyncRequest(
            upserts = dirty.filter { !it.deleted },
            tombstones = dirty.filter { it.deleted },
            since = since,
        )
        val req = authed("$baseUrl/api/drawings/sync")
            .post(apiJson.encodeToString(payload).toRequestBody(JSON_MEDIA))
            .build()
        return decodeDrawSyncResponse(execute(req))
    }

    fun pullLabels(cursor: Long): dev.cstraka.keeps.sync.LabelPullResponse {
        val req = authed("$baseUrl/api/labels?since=$cursor").get().build()
        return decodeLabelPullResponse(execute(req))
    }

    fun pushLabels(dirty: List<dev.cstraka.keeps.sync.Label>, since: Long): dev.cstraka.keeps.sync.LabelSyncResponse {
        val payload = LabelSyncRequest(
            upserts = dirty.filter { !it.deleted },
            tombstones = dirty.filter { it.deleted },
            since = since,
        )
        val req = authed("$baseUrl/api/labels/sync")
            .post(apiJson.encodeToString(payload).toRequestBody(JSON_MEDIA))
            .build()
        return decodeLabelSyncResponse(execute(req))
    }

    fun exchange(code: String, deviceName: String): EnrollResponse {
        val payload = ExchangeRequest(code = code, deviceName = deviceName)
        val req = Request.Builder()
            .url("$baseUrl/api/devices/exchange")
            .post(apiJson.encodeToString(payload).toRequestBody(JSON_MEDIA))
            .build()
        return apiJson.decodeFromString<EnrollResponse>(execute(req))
    }

    /** Rename this or a peer device (either gate; 404 when unknown). */
    fun rename(deviceId: String, deviceName: String) {
        val payload = dev.cstraka.keeps.sync.RenameRequest(deviceId, deviceName)
        val req = authed("$baseUrl/api/devices/rename")
            .post(apiJson.encodeToString(payload).toRequestBody(JSON_MEDIA))
            .build()
        execute(req)
    }

    /**
     * Rotate a device credential. Returns the fresh raw token (same shape
     * as enroll); the old token stops working immediately.
     */
    fun rotate(deviceId: String): EnrollResponse {
        val payload = dev.cstraka.keeps.sync.RotateRequest(deviceId)
        val req = authed("$baseUrl/api/devices/rotate")
            .post(apiJson.encodeToString(payload).toRequestBody(JSON_MEDIA))
            .build()
        return apiJson.decodeFromString<EnrollResponse>(execute(req))
    }

    fun listDevices(): dev.cstraka.keeps.sync.DeviceListResponse {
        val req = authed("$baseUrl/api/devices").get().build()
        return apiJson.decodeFromString(execute(req))
    }

    /** Canonical enrollment URL opened in the Custom Tab. */
    fun enrollCodeUrl(callback: String): String =
        "$baseUrl/api/devices/code?to=" + java.net.URLEncoder.encode(callback, "UTF-8")
}
