package dev.cstraka.keeps

import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import dev.cstraka.keeps.sync.exportNotes
import dev.cstraka.keeps.ui.EnrollScreen
import kotlinx.coroutines.launch
import dev.cstraka.keeps.ui.EnrollViewModel
import dev.cstraka.keeps.ui.NotesScreen
import dev.cstraka.keeps.ui.NotesViewModel
import dev.cstraka.keeps.ui.NoteFilter
import dev.cstraka.keeps.update.UpdatePhase
import dev.cstraka.keeps.update.UpdateWorker

class MainActivity : ComponentActivity() {

    private val app get() = application as KeepsApp

    private val notesModel: NotesViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                NotesViewModel(application, app.localStore, app.syncEngine, app.api, app.authStore) as T
        }
    }

    private val enrollModel: EnrollViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                EnrollViewModel(this@MainActivity, app.api, app.authStore) as T
        }
    }

    /**
     * Idea capture, voice first: the system speech recognizer's text is
     * saved at once as a note labelled Ideas. Cancelled or no recognizer
     * on the phone: the composer opens pre-labelled for typing.
     */
    private val recognizeIdea = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val text = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            ?.trim()
            .orEmpty()
        if (result.resultCode == RESULT_OK && text.isNotEmpty()) {
            notesModel.saveIdea(text) {
                Toast.makeText(this, "Idea saved", Toast.LENGTH_SHORT).show()
            }
        } else {
            notesModel.openIdeaComposer()
        }
    }

    private fun captureIdea() {
        if (!app.authStore.isEnrolled) return
        val speech = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Say the idea")
        if (speech.resolveActivity(packageManager) != null) {
            try {
                recognizeIdea.launch(speech)
                return
            } catch (e: android.content.ActivityNotFoundException) {
                // Fall through to typing.
            }
        }
        notesModel.openIdeaComposer()
    }

    /**
     * "Install unknown apps" opened from Keeps' own task, so Back lands
     * here and the update carries straight on to Android's prompt.
     */
    private val allowInstalls = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (packageManager.canRequestPackageInstalls()) UpdateWorker.checkNow(this, manual = true)
    }

    private fun checkForUpdate() {
        val phase = app.updater.state.value.phase
        if (phase == UpdatePhase.NEEDS_PERMISSION && !packageManager.canRequestPackageInstalls()) {
            runCatching { allowInstalls.launch(app.updater.permissionIntent()) }
        } else {
            UpdateWorker.checkNow(this, manual = true)
        }
    }

    /** Update notifications need this on Android 13+; asked once. */
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {}

    private fun askNotificationsOnce() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return
        val prefs = getSharedPreferences("update_check", MODE_PRIVATE)
        if (prefs.getBoolean("asked_notifications", false)) return
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        prefs.edit().putBoolean("asked_notifications", true).apply()
        notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    /** Flipped once enrollment completes so the UI swaps without relaunch. */
    private var enrolledVersion = mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleDeepLink(intent)
        handleComposeExtra(intent)
        handleSharedText(intent)
        handleUpdateExtra(intent)
        if (app.updater.takeResume()) restoreScreen()
        if (app.authStore.isEnrolled && savedInstanceState == null) askNotificationsOnce()
        setContent {
            val enrolled = remember(enrolledVersion.value) { app.authStore.isEnrolled }
            val theme by notesModel.theme.collectAsState()
            if (!enrolled) {
                val state by enrollModel.state.collectAsState()
                EnrollScreen(
                    state = state,
                    deviceName = enrollModel.deviceName.collectAsState().value,
                    onDeviceName = { enrollModel.deviceName.value = it },
                    onLogin = { enrollModel.startLogin() },
                    onRetry = { enrollModel.retry() },
                    theme = theme,
                )
            } else {
                val state by notesModel.state.collectAsState()
                val settingsMessage by notesModel.settingsMessage.collectAsState()
                val settingsBusy by notesModel.settingsBusy.collectAsState()
                val composerOpen by notesModel.composerOpen.collectAsState()
                // A silent self-update waits while unsaved text is on screen.
                val editorOpen = state.editing != null || composerOpen
                SideEffect { app.editorOpen = editorOpen }
                NotesScreen(
                    state = state,
                    needsLogin = state.syncStatus is dev.cstraka.keeps.sync.SyncStatus.NeedsLogin,
                    deviceName = app.authStore.deviceName(),
                    onQuery = notesModel::setQuery,
                    onFilter = notesModel::setFilter,
                    onLabelFilter = notesModel::setLabelFilter,
                    onCreate = notesModel::create,
                    composerOpen = composerOpen,
                    onComposerOpen = notesModel::setComposer,
                    ideaLabelId = notesModel.ideaLabelId.collectAsState().value,
                    onIdea = { captureIdea() },
                    onOpenEditor = notesModel::openEditor,
                    onCloseEditor = notesModel::closeEditor,
                    onSave = notesModel::save,
                    onSaveExtras = notesModel::saveExtras,
                    onCreateLabel = notesModel::createLabel,
                    onSaveDrawing = notesModel::saveDrawing,
                    onPin = notesModel::togglePin,
                    onArchive = notesModel::toggleArchive,
                    onTrash = notesModel::moveToTrash,
                    onRestore = notesModel::restore,
                    onDeleteForever = notesModel::deleteForever,
                    onSyncNow = notesModel::syncNow,
                    onSignOut = { signOut() },
                    deleteError = state.deleteError,
                    onClearDeleteError = notesModel::clearDeleteError,
                    onRename = notesModel::renameDevice,
                    onRotate = notesModel::rotateDevice,
                    settingsMessage = settingsMessage,
                    onClearSettingsMessage = notesModel::clearSettingsMessage,
                    settingsBusy = settingsBusy,
                    theme = theme,
                    onTheme = notesModel::setTheme,
                    appVersion = BuildConfig.VERSION_NAME,
                    update = app.updater.state.collectAsState().value,
                    onCheckUpdate = { checkForUpdate() },
                    onExport = { shareExport() },
                    onImportJson = notesModel::importJson,
                    onRenameLabel = notesModel::renameLabel,
                    onDeleteLabel = notesModel::deleteLabel,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
        handleComposeExtra(intent)
        handleSharedText(intent)
        handleUpdateExtra(intent)
    }

    override fun onStop() {
        super.onStop()
        saveScreen()
    }

    /** Where the owner was, so the app reopens there after a self-update. */
    private fun saveScreen() {
        val state = notesModel.state.value
        getSharedPreferences("ui_resume", MODE_PRIVATE).edit()
            .putString("filter", state.filter.name)
            .putString("label", state.labelFilter)
            .putString("query", state.query)
            .putString("note", state.editing?.id)
            .apply()
    }

    private fun restoreScreen() {
        val prefs = getSharedPreferences("ui_resume", MODE_PRIVATE)
        prefs.getString("filter", null)
            ?.let { name -> NoteFilter.entries.firstOrNull { it.name == name } }
            ?.let(notesModel::setFilter)
        notesModel.setLabelFilter(prefs.getString("label", null))
        notesModel.setQuery(prefs.getString("query", null).orEmpty())
        if (app.authStore.isEnrolled) prefs.getString("note", null)?.let(notesModel::openNoteById)
    }

    /** "Update ready" notification without the install permission yet. */
    private fun handleUpdateExtra(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_ALLOW_INSTALLS, false) != true) return
        intent.removeExtra(EXTRA_ALLOW_INSTALLS)
        if (packageManager.canRequestPackageInstalls()) {
            UpdateWorker.checkNow(this, manual = true)
        } else {
            runCatching { allowInstalls.launch(app.updater.permissionIntent()) }
        }
    }

    /** Shared text from any app becomes a note (enrolled only). Consumed once. */
    private fun handleSharedText(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        if (intent.type?.startsWith("text/") != true) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        intent.removeExtra(Intent.EXTRA_TEXT)
        intent.action = null
        if (!app.authStore.isEnrolled) return
        notesModel.importShared(text)
    }

    /** Backup JSON through the system share sheet (same shape as web export). */
    private fun shareExport() {
        lifecycleScope.launch {
            val json = exportNotes(app.localStore.all())
            val dir = java.io.File(cacheDir, "exports").apply { mkdirs() }
            val file = java.io.File(dir, "keeps-export.json")
            file.writeText(json)
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this@MainActivity, "${packageName}.fileprovider", file,
            )
            val send = Intent(Intent.ACTION_SEND)
                .setType("application/json")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(send, "Export notes"))
        }
    }

    private fun handleComposeExtra(intent: Intent?) {
        // Launcher shortcut / widget "Idea" button.
        if (intent?.action == ACTION_IDEA) {
            intent.action = null
            captureIdea()
            return
        }
        if (intent?.getBooleanExtra(EXTRA_COMPOSE, false) == true) {
            intent.removeExtra(EXTRA_COMPOSE)
            // Widget with a configured note opens it; otherwise a composer.
            intent.getStringExtra(EXTRA_NOTE_ID)?.let { id ->
                intent.removeExtra(EXTRA_NOTE_ID)
                if (app.authStore.isEnrolled) notesModel.openNoteById(id)
                return
            }
            notesModel.setComposer(true)
        }
    }

    private fun handleDeepLink(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme == "keeps" && uri.host == "enroll") {
            val code = uri.getQueryParameter("code") ?: return
            enrollModel.exchange(code) { enrolledVersion.value++ }
        }
    }

    companion object {
        const val EXTRA_COMPOSE = "dev.cstraka.keeps.EXTRA_COMPOSE"
        const val EXTRA_NOTE_ID = "dev.cstraka.keeps.EXTRA_NOTE_ID"
        /** Launcher shortcut + widget: capture an idea (voice first). */
        const val ACTION_IDEA = "dev.cstraka.keeps.action.IDEA"
        /** Update notification: open "install unknown apps" from here. */
        const val EXTRA_ALLOW_INSTALLS = "dev.cstraka.keeps.EXTRA_ALLOW_INSTALLS"
    }

    private fun signOut() {
        app.authStore.clear()
        enrolledVersion.value++
    }
}
