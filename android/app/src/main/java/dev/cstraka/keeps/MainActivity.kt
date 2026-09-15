package dev.cstraka.keeps

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.browser.customtabs.CustomTabsIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import dev.cstraka.keeps.sync.exportNotes
import dev.cstraka.keeps.ui.EnrollScreen
import kotlinx.coroutines.launch
import dev.cstraka.keeps.ui.EnrollViewModel
import dev.cstraka.keeps.ui.NotesScreen
import dev.cstraka.keeps.ui.NotesViewModel

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

    /** Flipped once enrollment completes so the UI swaps without relaunch. */
    private var enrolledVersion = mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleDeepLink(intent)
        handleComposeExtra(intent)
        handleSharedText(intent)
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
                    onUpdate = { openUpdatePage() },
                    onExport = { shareExport() },
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

        /** Update entry point shown in Settings (always the newest release). */
        const val LATEST_RELEASE_URL =
            "https://github.com/chris-straka/ccez-keeps/releases/latest"
    }

    /** Latest release page in a Custom Tab (same browser UX as enrollment). */
    private fun openUpdatePage() {
        try {
            CustomTabsIntent.Builder().build()
                .launchUrl(this, Uri.parse(LATEST_RELEASE_URL))
        } catch (e: Exception) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(LATEST_RELEASE_URL)))
        }
    }

    private fun signOut() {
        app.authStore.clear()
        enrolledVersion.value++
    }
}
