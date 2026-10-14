package dev.cstraka.keeps.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.cstraka.keeps.BuildConfig
import dev.cstraka.keeps.KeepsApp
import dev.cstraka.keeps.sync.UpdatePlan
import dev.cstraka.keeps.sync.isNewer
import dev.cstraka.keeps.sync.parseLatestRelease
import dev.cstraka.keeps.sync.parseSha256File
import dev.cstraka.keeps.sync.planUpdate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

/** Where the self-updater is; Settings renders it as one line. */
enum class UpdatePhase { IDLE, CHECKING, UP_TO_DATE, DOWNLOADING, READY, NEEDS_PERMISSION, WAITING_FOR_YOU, INSTALLING, FAILED }

data class UpdateUi(
    val phase: UpdatePhase = UpdatePhase.IDLE,
    /** Newest release tag seen, when it is newer than this build. */
    val tag: String? = null,
    /** Wall-clock millis of the last successful check; 0 = never. */
    val lastCheck: Long = 0L,
)

/**
 * Self-update: check the latest GitHub release, download its APK into
 * app storage (never Downloads), verify it (size, published SHA-256,
 * same package + signing certificate, newer version), then install
 * through a PackageInstaller session. With UPDATE_PACKAGES_WITHOUT_USER_ACTION
 * on Android 12+ the install is silent once Keeps is its own installer of
 * record (Android 16 already allows the first self-update silently);
 * otherwise Android asks once through the "Update ready" notification.
 *
 * Every failure is quiet: [run] returns false and WorkManager retries.
 * Notes are safe throughout: Room holds every edit and its dirty flag, the
 * sync queue is flushed (best effort) before installing, and a silent
 * install waits while the app is on screen or an editor is open.
 */
class Updater(private val app: KeepsApp, private val http: OkHttpClient) {
    private val prefs = app.getSharedPreferences("update_check", Context.MODE_PRIVATE)
    private val dir get() = File(app.filesDir, "updates")

    /** The periodic and app-start checks can fire together; one at a time. */
    private val lock = Mutex()

    private val _state = MutableStateFlow(
        UpdateUi(
            tag = prefs.getString("tag", null)?.takeIf { isNewer(it, BuildConfig.VERSION_NAME) },
            lastCheck = prefs.getLong("last_check", 0L),
        ),
    )
    val state: StateFlow<UpdateUi> = _state

    private fun set(phase: UpdatePhase, tag: String? = _state.value.tag) {
        _state.value = _state.value.copy(phase = phase, tag = tag)
    }

    /** Release JSON URL; only debug builds can point it elsewhere (tests). */
    val releaseUrl: String get() = BuildConfig.RELEASE_URL

    /**
     * One check-download-install pass. [manual] = the Settings row: install
     * now even while the app is open, and show Android's prompt at once.
     * Returns false on a transient failure (retry later).
     */
    suspend fun run(manual: Boolean): Boolean = withContext(Dispatchers.IO) { lock.withLock { pass(manual) } }

    private suspend fun pass(manual: Boolean): Boolean {
        set(UpdatePhase.CHECKING)
        val body = get(releaseUrl)
        val release = body?.let { parseLatestRelease(it) }
        if (release == null) {
            Log.i(TAG, "check failed: no release from $releaseUrl")
            set(UpdatePhase.FAILED)
            return false
        }
        val now = System.currentTimeMillis()
        prefs.edit().putLong("last_check", now).putString("tag", release.tag_name).apply()
        _state.value = _state.value.copy(lastCheck = now)
        val plan = planUpdate(release, BuildConfig.VERSION_NAME)
        if (plan == null) {
            dir.deleteRecursively()
            cancelNotification()
            set(UpdatePhase.UP_TO_DATE, tag = null)
            return true
        }
        set(UpdatePhase.DOWNLOADING, plan.tag)
        val apk = fetchVerified(plan)
        if (apk == null) {
            Log.i(TAG, "${plan.tag}: download or verification failed; retrying later")
            set(UpdatePhase.FAILED)
            return false
        }
        return install(apk, plan.tag, manual)
    }

    /** Cached APK if it still verifies, else a fresh download; null = failed. */
    private fun fetchVerified(plan: UpdatePlan): File? {
        dir.mkdirs()
        // One release at a time: anything else in the folder is stale.
        dir.listFiles()?.filter { it.name != plan.apk.name }?.forEach { it.delete() }
        val apk = File(dir, plan.apk.name)
        val sha = plan.sha256 ?: plan.checksumAsset?.let { asset ->
            // A published checksum we can't read is a retry, not a skip.
            get(asset.browser_download_url)?.let { parseSha256File(it, plan.apk.name) } ?: return null
        }
        if (apk.exists() && verify(apk, plan.apk.size, sha)) return apk
        val part = File(dir, plan.apk.name + ".part")
        try {
            http.newCall(Request.Builder().url(plan.apk.browser_download_url).build()).execute().use { res ->
                if (!res.isSuccessful) return null
                val src = res.body ?: return null
                part.outputStream().use { out -> src.byteStream().copyTo(out) }
            }
        } catch (_: Exception) {
            part.delete()
            return null
        }
        if (!part.renameTo(apk) || !verify(apk, plan.apk.size, sha)) {
            part.delete()
            apk.delete()
            return null
        }
        return apk
    }

    /** Size, checksum, then the APK itself: our package, newer, same signer. */
    private fun verify(apk: File, size: Long, sha256: String?): Boolean {
        if (apk.length() != size) return false.also { Log.i(TAG, "size ${apk.length()} != $size") }
        if (sha256 != null && sha256Of(apk) != sha256) return false.also { Log.i(TAG, "sha256 mismatch") }
        val pm = app.packageManager
        val archive = pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES) ?: return false
        if (archive.packageName != app.packageName) return false
        val installed = pm.getPackageInfo(app.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        if (!isNewer(archive.versionName ?: "", BuildConfig.VERSION_NAME)) return false
        if (archive.longVersionCode < installed.longVersionCode) return false
        val same = signers(archive).isNotEmpty() && signers(archive) == signers(installed)
        if (!same) Log.i(TAG, "signing certificate differs from the installed app")
        return same
    }

    private fun signers(info: PackageInfo): Set<String> {
        val signing = info.signingInfo ?: return emptySet()
        val certs = if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory
        return certs.orEmpty().map { it.toCharsString() }.toSet()
    }

    private fun sha256Of(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun get(url: String): String? = try {
        http.newCall(
            Request.Builder().url(url).header("Accept", "application/vnd.github+json").build(),
        ).execute().use { res ->
            if (res.isSuccessful) res.body?.string()?.take(1_000_000) else null
        }
    } catch (_: Exception) {
        null
    }

    private suspend fun install(apk: File, tag: String, manual: Boolean): Boolean {
        if (!app.packageManager.canRequestPackageInstalls()) {
            notifyNeedsPermission(tag)
            set(UpdatePhase.NEEDS_PERMISSION, tag)
            return true
        }
        // An install restarts the app; never pull it out from under the
        // owner. Android 16 skips the prompt even for the first self-update,
        // so assume any install may be silent. KeepsApp re-runs this when
        // the app is left (and an open editor keeps it waiting).
        if (!manual && (app.isForeground || app.editorOpen)) {
            set(UpdatePhase.READY, tag)
            return true
        }
        // Room already holds every edit; pushing now just saves a round
        // after the restart. Bounded so a slow server can't block updates.
        withTimeoutOrNull(20_000) { runCatching { app.syncEngine.flush() } }
        prefs.edit().putBoolean(KEY_RESUME, true).putBoolean(KEY_MANUAL, manual).apply()
        val installer = app.packageManager.packageInstaller
        installer.mySessions.forEach { runCatching { installer.abandonSession(it.sessionId) } }
        return try {
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(app.packageName)
            params.setSize(apk.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            val id = installer.createSession(params)
            installer.openSession(id).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val status = Intent(app, InstallReceiver::class.java)
                    .setAction(InstallReceiver.ACTION_STATUS)
                    .putExtra(InstallReceiver.EXTRA_TAG, tag)
                val pending = PendingIntent.getBroadcast(
                    app, id, status,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                set(UpdatePhase.INSTALLING, tag)
                session.commit(pending.intentSender)
            }
            true
        } catch (e: Exception) {
            Log.i(TAG, "install session failed", e)
            set(UpdatePhase.FAILED, tag)
            false
        }
    }

    /** Android wants a tap: one "Update ready" notification opens its prompt. */
    fun onNeedsUserAction(confirm: Intent, tag: String) {
        set(UpdatePhase.WAITING_FOR_YOU, tag)
        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Tapped Check in Settings: Android's prompt opens right away.
        if (prefs.getBoolean(KEY_MANUAL, false) && app.isForeground) {
            runCatching { app.startActivity(confirm) }
        }
        val tap = PendingIntent.getActivity(
            app, 1, confirm,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        notify(
            title = "Update ready",
            text = "Keeps $tag is downloaded. Tap to install.",
            tap = tap,
        )
    }

    fun onInstallFailed(tag: String?, status: Int) {
        // A broken or mismatched file is never retried as-is.
        if (status == PackageInstaller.STATUS_FAILURE_INVALID ||
            status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ||
            status == PackageInstaller.STATUS_FAILURE_CONFLICT
        ) {
            dir.deleteRecursively()
        }
        prefs.edit().remove(KEY_RESUME).remove(KEY_MANUAL).apply()
        set(UpdatePhase.FAILED, tag)
    }

    /**
     * The new version is running (MY_PACKAGE_REPLACED). Android 10+ won't
     * let an app start itself from here, so when the owner started the
     * install (and is waiting on it) one tap reopens the app where it was.
     */
    fun onReplaced() {
        dir.deleteRecursively()
        set(UpdatePhase.UP_TO_DATE, tag = null)
        if (!prefs.getBoolean(KEY_MANUAL, false)) {
            cancelNotification()
            return
        }
        prefs.edit().remove(KEY_MANUAL).apply()
        val open = Intent(app, dev.cstraka.keeps.MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        notify(
            title = "Keeps updated to ${BuildConfig.VERSION_NAME}",
            text = "Tap to pick up where you left off.",
            tap = PendingIntent.getActivity(app, 3, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
            id = UPDATED_ID,
        )
    }

    /** Read once by the UI after an update: reopen where it was. */
    fun takeResume(): Boolean {
        val resume = prefs.getBoolean(KEY_RESUME, false)
        if (resume) {
            prefs.edit().remove(KEY_RESUME).apply()
            NotificationManagerCompat.from(app).cancel(UPDATED_ID)
        }
        return resume
    }

    private fun notifyNeedsPermission(tag: String) {
        // Through MainActivity so Back from Settings returns to Keeps and
        // the install carries on (see MainActivity.allowInstalls).
        val open = Intent(app, dev.cstraka.keeps.MainActivity::class.java)
            .putExtra(dev.cstraka.keeps.MainActivity.EXTRA_ALLOW_INSTALLS, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val tap = PendingIntent.getActivity(
            app, 2, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        notify(
            title = "Update ready",
            text = "Tap to allow Keeps to install its updates.",
            tap = tap,
        )
    }

    /** The settings screen for "install unknown apps" (Settings row). */
    fun permissionIntent(): Intent = Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:${app.packageName}"),
    )

    private fun notify(title: String, text: String, tap: PendingIntent, id: Int = NOTIFICATION_ID) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return // Settings still shows the state; the next app open asks.
        }
        val n = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        try {
            NotificationManagerCompat.from(app).notify(id, n)
        } catch (_: SecurityException) {
        }
    }

    private fun cancelNotification() = NotificationManagerCompat.from(app).cancel(NOTIFICATION_ID)

    companion object {
        const val CHANNEL_ID = "updates"
        private const val TAG = "KeepsUpdate"
        private const val NOTIFICATION_ID = 0x0D0
        /** "Keeps updated": outlives the new version's first check. */
        private const val UPDATED_ID = 0x0D1
        private const val KEY_RESUME = "resume_after_update"
        private const val KEY_MANUAL = "manual_install"

        fun createChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "App updates", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
    }
}
