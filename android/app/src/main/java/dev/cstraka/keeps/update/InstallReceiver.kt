package dev.cstraka.keeps.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import dev.cstraka.keeps.KeepsApp

/** PackageInstaller session results, and MY_PACKAGE_REPLACED once the new version is in. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as KeepsApp
        when (intent.action) {
            ACTION_STATUS -> {
                val tag = intent.getStringExtra(EXTRA_TAG)
                when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        @Suppress("DEPRECATION")
                        val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                        app.updater.onNeedsUserAction(confirm, tag ?: "")
                    }
                    PackageInstaller.STATUS_SUCCESS -> Unit // MY_PACKAGE_REPLACED follows.
                    else -> app.updater.onInstallFailed(tag, status)
                }
            }
            Intent.ACTION_MY_PACKAGE_REPLACED -> app.updater.onReplaced()
        }
    }

    companion object {
        const val ACTION_STATUS = "dev.cstraka.keeps.action.INSTALL_STATUS"
        const val EXTRA_TAG = "dev.cstraka.keeps.EXTRA_TAG"
    }
}
