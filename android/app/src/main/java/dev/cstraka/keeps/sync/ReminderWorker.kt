package dev.cstraka.keeps.sync

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.cstraka.keeps.MainActivity
import java.util.concurrent.TimeUnit

/**
 * Fires a note reminder. A plain one-shot with an initial delay at
 * reminderAt: it survives offline and needs no exact-alarm permission.
 * Tapping the notification opens the app; clearing the reminder cancels
 * the pending work. When POST_NOTIFICATIONS is denied the notification is
 * skipped and the in-app overdue state remains the fallback.
 */
class ReminderWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val noteId = inputData.getString(KEY_NOTE_ID) ?: return Result.failure()
        val title = inputData.getString(KEY_TITLE).orEmpty()
        val body = inputData.getString(KEY_BODY).orEmpty()
        showReminder(noteId, title, body)
        return Result.success()
    }

    private fun showReminder(noteId: String, title: String, body: String) {
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val tap = PendingIntent.getActivity(
            applicationContext, noteId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = when {
            title.isNotBlank() && body.isNotBlank() -> body
            title.isNotBlank() -> title
            body.isNotBlank() -> body
            else -> "Reminder"
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title.ifBlank { "Keeps reminder" })
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try {
            NotificationManagerCompat.from(applicationContext)
                .notify("reminder-$noteId".hashCode(), notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS denied: the in-app overdue state remains.
        }
    }

    companion object {
        const val CHANNEL_ID = "reminders"
        private const val KEY_NOTE_ID = "noteId"
        private const val KEY_TITLE = "title"
        private const val KEY_BODY = "body"

        private fun workName(noteId: String) = "reminder-$noteId"

        /** Schedule (or reschedule) the fire at [atMillis]; past times fire at once. */
        fun schedule(context: Context, noteId: String, title: String, body: String, atMillis: Long) {
            val delay = (atMillis - System.currentTimeMillis()).coerceAtLeast(0L)
            val input = Data.Builder()
                .putString(KEY_NOTE_ID, noteId)
                .putString(KEY_TITLE, title)
                .putString(KEY_BODY, body)
                .build()
            val req = OneTimeWorkRequestBuilder<ReminderWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setInputData(input)
                .addTag(workName(noteId))
                .build()
            WorkManager.getInstance(context).enqueue(req)
        }

        fun cancel(context: Context, noteId: String) {
            WorkManager.getInstance(context).cancelAllWorkByTag(workName(noteId))
        }
    }
}
