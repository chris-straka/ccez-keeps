package dev.cstraka.keeps.update

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * Background self-update: every 6 hours and at each app start, on any
 * network. Failures retry with WorkManager's backoff, silently.
 */
class UpdateWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as dev.cstraka.keeps.KeepsApp
        val manual = inputData.getBoolean(MANUAL, false)
        return try {
            when {
                app.updater.run(manual) -> Result.success()
                runAttemptCount >= 5 -> Result.failure()
                else -> Result.retry()
            }
        } catch (_: Exception) {
            if (runAttemptCount >= 5) Result.failure() else Result.retry()
        }
    }

    companion object {
        private const val MANUAL = "manual"
        private const val PERIODIC = "update-periodic"
        private const val NOW = "update-now"

        private fun connected() = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        fun schedulePeriodic(context: Context) {
            val req = PeriodicWorkRequestBuilder<UpdateWorker>(6, TimeUnit.HOURS)
                .setConstraints(connected())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        /** App start / leaving the app: KEEP so repeats coalesce. Manual replaces. */
        fun checkNow(context: Context, manual: Boolean = false) {
            val req = OneTimeWorkRequestBuilder<UpdateWorker>()
                .setConstraints(connected())
                .setInputData(workDataOf(MANUAL to manual))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                NOW,
                if (manual) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                req,
            )
        }
    }
}
