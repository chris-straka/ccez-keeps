package dev.cstraka.keeps.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Snooze buttons on reminder notifications. Reuses [ReminderWorker.schedule]
 * so there is exactly one timing path; the fired work is already consumed,
 * so rescheduling never duplicates. Explicit intents only (not exported).
 */
class SnoozeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val noteId = intent.getStringExtra(EXTRA_NOTE_ID) ?: return
        val delayMin = intent.getIntExtra(EXTRA_DELAY_MINUTES, 0)
        if (delayMin <= 0) return
        ReminderWorker.schedule(
            context,
            noteId,
            intent.getStringExtra(EXTRA_TITLE).orEmpty(),
            intent.getStringExtra(EXTRA_BODY).orEmpty(),
            System.currentTimeMillis() + delayMin * 60_000L,
        )
    }

    companion object {
        const val EXTRA_NOTE_ID = "dev.cstraka.keeps.EXTRA_SNOOZE_NOTE_ID"
        const val EXTRA_TITLE = "dev.cstraka.keeps.EXTRA_SNOOZE_TITLE"
        const val EXTRA_BODY = "dev.cstraka.keeps.EXTRA_SNOOZE_BODY"

        /**
         * Snooze length in whole minutes. Buttons use 10/60; tests may use
         * any positive value through the same broadcast contract.
         */
        const val EXTRA_DELAY_MINUTES = "dev.cstraka.keeps.EXTRA_SNOOZE_DELAY_MINUTES"
    }
}
