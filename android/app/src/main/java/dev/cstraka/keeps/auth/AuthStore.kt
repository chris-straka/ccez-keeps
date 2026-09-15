package dev.cstraka.keeps.auth

import android.content.Context
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * The phone's own credential (plan-android.md flow 2). The device token is
 * minted once at enrollment and lives in EncryptedSharedPreferences; the
 * cursor/pushMark watermarks are non-sensitive and live in [LocalStore].
 */
class AuthStore(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context.applicationContext,
        "device_auth",
        MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    val isEnrolled: Boolean get() = token() != null

    fun token(): String? = prefs.getString("token", null)

    fun deviceId(): String? = prefs.getString("device_id", null)

    fun deviceName(): String = prefs.getString("device_name", "") ?: ""

    fun saveEnrollment(deviceId: String, token: String, deviceName: String) {
        prefs.edit {
            putString("device_id", deviceId)
            putString("token", token)
            putString("device_name", deviceName)
        }
    }

    /** Rotation keeps the id/name and swaps only the raw token. */
    fun saveToken(token: String) {
        prefs.edit { putString("token", token) }
    }

    fun saveDeviceName(deviceName: String) {
        prefs.edit { putString("device_name", deviceName) }
    }

    fun clear() {
        prefs.edit { clear() }
    }
}

/** Test seam: token source for the API client. */
fun interface TokenProvider {
    fun token(): String?
}
