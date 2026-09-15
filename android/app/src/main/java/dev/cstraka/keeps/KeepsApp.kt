package dev.cstraka.keeps

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.room.Room
import androidx.work.Configuration
import dev.cstraka.keeps.auth.AuthStore
import dev.cstraka.keeps.data.KeepsDatabase
import dev.cstraka.keeps.data.LocalStore
import dev.cstraka.keeps.data.MIGRATION_1_2
import dev.cstraka.keeps.data.MIGRATION_2_3
import dev.cstraka.keeps.sync.KeepsApi
import dev.cstraka.keeps.sync.ReminderWorker
import dev.cstraka.keeps.sync.SyncEngine
import dev.cstraka.keeps.sync.SyncWorker
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Manual DI container. No Hilt: the graph is five objects and a single-user
 * app does not need a DI framework to hold them.
 */
class KeepsApp : Application(), Configuration.Provider {
    lateinit var authStore: AuthStore
        private set
    lateinit var localStore: LocalStore
        private set
    lateinit var api: KeepsApi
        private set
    lateinit var syncEngine: SyncEngine
        private set

    override fun onCreate() {
        super.onCreate()
        authStore = AuthStore(this)
        val db = Room.databaseBuilder(this, KeepsDatabase::class.java, "keeps")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .build()
        val meta = getSharedPreferences("sync_meta", MODE_PRIVATE)
        localStore = LocalStore(db.noteDao(), db.drawingDao(), db.labelDao(), meta)
        createReminderChannel()
        val http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
        api = KeepsApi(BuildConfig.BASE_URL, http, authStore::token)
        syncEngine = SyncEngine(this, localStore, api)
        SyncWorker.schedulePeriodic(this)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    private fun createReminderChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                ReminderWorker.CHANNEL_ID,
                "Reminders",
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
    }
}
