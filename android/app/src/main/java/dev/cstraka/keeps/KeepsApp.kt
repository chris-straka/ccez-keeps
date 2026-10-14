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
import dev.cstraka.keeps.data.MIGRATION_3_4
import dev.cstraka.keeps.data.MIGRATION_4_5
import dev.cstraka.keeps.data.MIGRATION_5_6
import dev.cstraka.keeps.data.MIGRATION_2_3
import dev.cstraka.keeps.sync.KeepsApi
import dev.cstraka.keeps.sync.ReminderWorker
import dev.cstraka.keeps.sync.SyncEngine
import dev.cstraka.keeps.sync.SyncWorker
import dev.cstraka.keeps.update.UpdatePhase
import dev.cstraka.keeps.update.UpdateWorker
import dev.cstraka.keeps.update.Updater
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
    lateinit var updater: Updater
        private set

    /** Some activity is started: a silent update would yank the screen. */
    val isForeground: Boolean get() = startedActivities > 0
    private var startedActivities = 0

    /** Set by MainActivity while an editor or composer holds unsaved text. */
    @Volatile var editorOpen = false

    override fun onCreate() {
        super.onCreate()
        authStore = AuthStore(this)
        val db = Room.databaseBuilder(this, KeepsDatabase::class.java, "keeps")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
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
        Updater.createChannel(this)
        updater = Updater(this, http)
        UpdateWorker.schedulePeriodic(this)
        UpdateWorker.checkNow(this)
        registerActivityLifecycleCallbacks(object : SimpleActivityCallbacks() {
            override fun onActivityStarted(activity: android.app.Activity) { startedActivities++ }
            override fun onActivityStopped(activity: android.app.Activity) {
                startedActivities--
                // A downloaded update waited for the owner to leave: go now.
                if (startedActivities == 0 && updater.state.value.phase == UpdatePhase.READY) {
                    UpdateWorker.checkNow(this@KeepsApp)
                }
            }
        })
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    private abstract class SimpleActivityCallbacks : ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: android.app.Activity, state: android.os.Bundle?) {}
        override fun onActivityStarted(activity: android.app.Activity) {}
        override fun onActivityResumed(activity: android.app.Activity) {}
        override fun onActivityPaused(activity: android.app.Activity) {}
        override fun onActivityStopped(activity: android.app.Activity) {}
        override fun onActivitySaveInstanceState(activity: android.app.Activity, state: android.os.Bundle) {}
        override fun onActivityDestroyed(activity: android.app.Activity) {}
    }

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
