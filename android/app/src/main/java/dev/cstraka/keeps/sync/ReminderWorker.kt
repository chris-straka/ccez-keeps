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
import dev.cstraka.keeps.KeepsApp
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
        val at = inputData.getLong(KEY_AT, 0L)
        val repeat = inputData.getString(KEY_REPEAT)
        showReminder(noteId, title, body)
        if (repeat == "daily" || repeat == "weekly") {
            advanceAndReschedule(noteId, at, repeat)
        }
        return Result.success()
    }

    /**
     * Repeat: advance the row past this firing and schedule the next one.
     * Guarded on the exact fired time so a newer edit (which cancels this
     * work on save) never gets clobbered. The advanced row syncs out via
     * an immediate push so every client converges on the next fire.
     */
    private suspend fun advanceAndReschedule(noteId: String, firedAt: Long, repeat: String) {
        val app = applicationContext as KeepsApp
        val current = app.localStore.noteById(noteId) ?: return
        if (current.deleted || current.reminderAt != firedAt || current.repeat != repeat) return
        val next = nextRepeat(firedAt, repeat)
        app.localStore.put(
            current.copy(reminderAt = next, updatedAt = System.currentTimeMillis()),
        )
        schedule(applicationContext, noteId, current.title, current.body, next, repeat)
        SyncWorker.scheduleNow(applicationContext)
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
            .addAction(0, "10 min", snoozeAction(noteId, title, body, 10))
            .addAction(0, "1 hr", snoozeAction(noteId, title, body, 60))
            .build()
        try {
            NotificationManagerCompat.from(applicationContext)
                .notify("reminder-$noteId".hashCode(), notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS denied: the in-app overdue state remains.
        }
    }

    private fun snoozeAction(noteId: String, title: String, body: String, delayMin: Int): PendingIntent {
        val intent = Intent(applicationContext, SnoozeReceiver::class.java).apply {
            putExtra(SnoozeReceiver.EXTRA_NOTE_ID, noteId)
            putExtra(SnoozeReceiver.EXTRA_TITLE, title)
            putExtra(SnoozeReceiver.EXTRA_BODY, body)
            putExtra(SnoozeReceiver.EXTRA_DELAY_MINUTES, delayMin)
        }
        return PendingIntent.getBroadcast(
            applicationContext, noteId.hashCode() * 31 + delayMin, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val CHANNEL_ID = "reminders"
        private const val KEY_NOTE_ID = "noteId"
        private const val KEY_TITLE = "title"
        private const val KEY_BODY = "body"
        private const val KEY_AT = "at"
        private const val KEY_REPEAT = "repeat"

        private fun workName(noteId: String) = "reminder-$noteId"

        /** Schedule (or reschedule) the fire at [atMillis]; past times fire at once. */
        fun schedule(
            context: Context,
            noteId: String,
            title: String,
            body: String,
            atMillis: Long,
            repeat: String? = null,
        ) {
            val delay = (atMillis - System.currentTimeMillis()).coerceAtLeast(0L)
            val input = Data.Builder()
                .putString(KEY_NOTE_ID, noteId)
                .putString(KEY_TITLE, title)
                .putString(KEY_BODY, body)
                .putLong(KEY_AT, atMillis)
                .putString(KEY_REPEAT, repeat)
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
