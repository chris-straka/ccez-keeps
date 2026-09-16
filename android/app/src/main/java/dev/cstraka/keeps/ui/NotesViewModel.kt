package dev.cstraka.keeps.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.cstraka.keeps.auth.AuthStore
import dev.cstraka.keeps.data.LocalStore
import dev.cstraka.keeps.data.deleteLabel
import dev.cstraka.keeps.data.renameLabel
import dev.cstraka.keeps.data.NoteEntity
import dev.cstraka.keeps.data.toDrawing
import dev.cstraka.keeps.data.toLabel
import dev.cstraka.keeps.data.toNote
import dev.cstraka.keeps.sync.ApiException
import dev.cstraka.keeps.sync.Attachment
import dev.cstraka.keeps.sync.ChecklistItem
import dev.cstraka.keeps.sync.Drawing
import dev.cstraka.keeps.sync.DrawingStroke
import dev.cstraka.keeps.sync.KeepsApi
import dev.cstraka.keeps.sync.Label
import dev.cstraka.keeps.sync.Note
import dev.cstraka.keeps.sync.NoteLimits
import dev.cstraka.keeps.sync.ReminderWorker
import dev.cstraka.keeps.sync.isAgendaNote
import dev.cstraka.keeps.sync.isNewer
import dev.cstraka.keeps.sync.isNote
import dev.cstraka.keeps.sync.mergeImportNotes
import dev.cstraka.keeps.sync.parseExport
import dev.cstraka.keeps.sync.parseLatestTag
import dev.cstraka.keeps.sync.sortAgenda
import dev.cstraka.keeps.sync.SyncEngine
import dev.cstraka.keeps.sync.SyncStatus
import dev.cstraka.keeps.sync.SyncWorker
import dev.cstraka.keeps.sync.rankNotes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class NoteFilter { NOTES, REMINDERS, ARCHIVE, TRASH }

data class NotesUiState(
    val notes: List<Note> = emptyList(),
    val query: String = "",
    val filter: NoteFilter = NoteFilter.NOTES,
    val syncStatus: SyncStatus = SyncStatus.Idle,
    val editing: Note? = null,
    val deleteError: Boolean = false,
    val drawings: Map<String, Drawing> = emptyMap(),
    val labels: List<Label> = emptyList(),
    /** Selected drawer label id; null = no label filter. */
    val labelFilter: String? = null,
)

/**
 * Single screen model. Every mutation writes Room first (instant UI via
 * Flow), then schedules a one-shot sync; the engine converges with the
 * server by LWW exactly like the web SyncEngine.
 */
class NotesViewModel(
    private val app: android.app.Application,
    private val store: LocalStore,
    private val engine: SyncEngine,
    private val api: KeepsApi? = null,
    private val auth: AuthStore? = null,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val filter = MutableStateFlow(NoteFilter.NOTES)
    private val editing = MutableStateFlow<Note?>(null)
    private val deleteError = MutableStateFlow(false)
    private val labelFilter = MutableStateFlow<String?>(null)

    private val _settingsMessage = MutableStateFlow<String?>(null)
    val settingsMessage: StateFlow<String?> = _settingsMessage

    private val _settingsBusy = MutableStateFlow(false)
    val settingsBusy: StateFlow<Boolean> = _settingsBusy

    private val themeStore = ThemeStore(app)
    private val _theme = MutableStateFlow(themeStore.get())
    val theme: StateFlow<ThemeMode> = _theme

    fun setTheme(mode: ThemeMode) {
        themeStore.set(mode)
        _theme.value = mode
    }

    private val _composerOpen = MutableStateFlow(false)
    val composerOpen: StateFlow<Boolean> = _composerOpen

    /**
     * Newest release tag when it is newer than this build; null means up
     * to date or unknown (offline/errors fail silent, never nudge).
     * Checked at most once per day; the answer rides a process restart.
     */
    private val _updateTag = MutableStateFlow<String?>(null)
    val updateTag: StateFlow<String?> = _updateTag

    init {
        checkForUpdate()
    }

    private fun checkForUpdate() = viewModelScope.launch {
        val prefs = app.getSharedPreferences("update_check", android.content.Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("last_check", 0L) < 24 * 60 * 60 * 1000L) {
            prefs.getString("tag", null)?.let { cached ->
                if (isNewer(cached, dev.cstraka.keeps.BuildConfig.VERSION_NAME)) {
                    _updateTag.value = cached
                }
            }
            return@launch
        }
        val tag = withContext(kotlinx.coroutines.Dispatchers.IO) { fetchLatestTag() }
        prefs.edit().putLong("last_check", now).apply()
        if (tag == null) return@launch
        prefs.edit().putString("tag", tag).apply()
        if (isNewer(tag, dev.cstraka.keeps.BuildConfig.VERSION_NAME)) {
            _updateTag.value = tag
        }
    }

    private fun fetchLatestTag(): String? {
        return try {
            val url = java.net.URL(
                "https://api.github.com/repos/chris-straka/ccez-keeps/releases/latest",
            )
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }.take(100_000)
            parseLatestTag(body)
        } catch (_: Exception) {
            null
        }
    }

    /** Widget + notification taps open a blank composer through here. */
    fun setComposer(open: Boolean) {
        _composerOpen.value = open
    }

    /**
     * Text shared in from another app. First line becomes the title when
     * multiline (links + commentary pattern); otherwise the whole text is
     * the body. Caller gates on enrollment.
     */
    fun importShared(text: String) = viewModelScope.launch {
        val clean = text.trim().take(100_000)
        if (clean.isEmpty()) return@launch
        val nl = clean.indexOf('\n')
        val title = if (nl == -1) "" else clean.substring(0, nl).trim().take(200)
        val body = if (nl == -1) clean else clean.substring(nl + 1).trim()
        create(title, body)
    }

    // combine() tops out at 5 flows, so fold 7 inputs through a quad and
    // a triple.
    private data class Basics(
        val rows: List<NoteEntity>,
        val drawings: Map<String, Drawing>,
        val q: String,
        val f: NoteFilter,
    )
    private data class Ephemera(val status: SyncStatus, val ed: Note?, val delErr: Boolean)
    private data class Core(val basics: Basics, val ephemera: Ephemera)
    private data class LabelBits(val labels: List<Label>, val filter: String?)

    val state: StateFlow<NotesUiState> = combine(
        combine(store.observeAll(), store.observeDrawings(), query, filter) { rows, draws, q, f ->
            Basics(rows, draws.map { it.toDrawing() }.associateBy { it.id }, q, f)
        },
        combine(engine.status, editing, deleteError, ::Ephemera),
    ) { basics, eph ->
        Core(basics, eph)
    }.combine(
        combine(store.observeLabels(), labelFilter) { rows, f ->
            LabelBits(rows.map { it.toLabel() }, f)
        },
    ) { core, bits ->
        val liveLabels = bits.labels.filter { it.deleted.not() }
        val all = core.basics.rows.map { it.toNote() }
        val lf = bits.filter
        val bucketed = all.filter { note ->
            val inBucket = when (core.basics.f) {
                NoteFilter.NOTES -> !note.archived && !note.deleted
                NoteFilter.REMINDERS -> isAgendaNote(note)
                NoteFilter.ARCHIVE -> note.archived && !note.deleted
                NoteFilter.TRASH -> note.deleted
            }
            inBucket && (lf == null || lf in note.labelIds)
        }
        // Agenda order is fire time (overdue first); search still ranks.
        val ordered = if (core.basics.f == NoteFilter.REMINDERS && core.basics.q.isBlank()) {
            sortAgenda(bucketed)
        } else {
            bucketed
        }
        val visible = rankNotes(
            core.basics.q, ordered,
            liveLabels.associate { it.id to it.name },
        )
        NotesUiState(
            visible, core.basics.q, core.basics.f, core.ephemera.status,
            core.ephemera.ed, core.ephemera.delErr, core.basics.drawings, liveLabels, lf,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NotesUiState())

    private fun touch() {
        SyncWorker.scheduleNow(app)
        // Every local write rebinds the list widget (no-op with none placed).
        dev.cstraka.keeps.widget.NotesListWidget.refreshAll(app)
    }

    fun setQuery(q: String) { query.value = q }
    fun setFilter(f: NoteFilter) { filter.value = f }
    fun setLabelFilter(id: String?) { labelFilter.value = id }
    fun openEditor(note: Note?) { editing.value = note }

    /** Widget/notification entry points that only know an id. */
    fun openNoteById(id: String) = viewModelScope.launch {
        store.noteById(id)?.let { openEditor(it) }
    }
    fun closeEditor() { editing.value = null }
    fun clearDeleteError() { deleteError.value = false }

    fun create(
        title: String,
        body: String,
        labelIds: List<String> = emptyList(),
        reminderAt: Long? = null,
        repeat: String? = null,
        checklist: List<ChecklistItem>? = null,
        attachments: List<Attachment> = emptyList(),
    ) = viewModelScope.launch {
        val items = checklist?.take(NoteLimits.MAX_CHECKLIST_ITEMS).orEmpty()
        val files = attachments.take(NoteLimits.MAX_ATTACHMENTS)
        if (title.isBlank() && body.isBlank() && labelIds.isEmpty() && reminderAt == null &&
            items.isEmpty() && files.isEmpty()
        ) return@launch
        val note = store.create(
            title, body, labelIds.take(20), reminderAt, repeat,
            checklist?.take(NoteLimits.MAX_CHECKLIST_ITEMS), files,
        )
        if (reminderAt != null) {
            ReminderWorker.schedule(app, note.id, note.title, note.body, reminderAt, repeat)
        }
        touch()
    }

    fun save(
        note: Note,
        title: String,
        body: String,
        color: String,
        checklist: List<ChecklistItem>? = null,
        attachments: List<Attachment> = emptyList(),
    ) = viewModelScope.launch {
        val current = store.all().firstOrNull { it.id == note.id } ?: note
        val candidate = current.copy(
            title = title, body = body, color = color,
            checklist = checklist?.take(NoteLimits.MAX_CHECKLIST_ITEMS),
            attachments = attachments.take(NoteLimits.MAX_ATTACHMENTS),
            updatedAt = now(),
        )
        if (!isNote(candidate)) return@launch
        store.put(candidate)
        refreshReminder(candidate)
        editing.value = null
        touch()
    }

    /** Persist editor label/reminder picks; reschedules or clears the firing. */
    fun saveExtras(
        note: Note,
        labelIds: List<String>,
        reminderAt: Long?,
        repeat: String? = null,
    ) = viewModelScope.launch {
        val current = store.all().firstOrNull { it.id == note.id } ?: return@launch
        val updated = current.copy(
            labelIds = labelIds.take(20), reminderAt = reminderAt,
            repeat = repeat, updatedAt = now(),
        )
        store.put(updated)
        refreshReminder(updated)
        touch()
    }

    fun createLabel(name: String, onDone: (Label) -> Unit = {}) = viewModelScope.launch {
        val trimmed = name.trim().take(120)
        if (trimmed.isEmpty()) return@launch
        val label = store.createLabel(trimmed)
        touch()
        onDone(label)
    }

    /** Drawer long-press rename; blank names are a no-op (UI disables). */
    fun renameLabel(id: String, name: String) = viewModelScope.launch {
        if (store.renameLabel(id, name) != null) touch()
    }

    /** Drawer long-press delete; clears the drawer filter when it pointed here. */
    fun deleteLabel(id: String) = viewModelScope.launch {
        store.deleteLabel(id)
        if (labelFilter.value == id) labelFilter.value = null
        touch()
    }

    /**
     * Backup import from Settings. Parses the same `{version: 1, notes}`
     * shape [exportNotes] writes, merges LWW per id, and stamps only the
     * changed rows fresh so the push lane picks them up as dirty.
     */
    fun importJson(raw: String) = viewModelScope.launch {
        val incoming = try {
            parseExport(raw).notes
        } catch (_: Exception) {
            _settingsMessage.value = "Couldn't read that backup — is it a Keeps export?"
            return@launch
        }
        if (incoming.isEmpty()) {
            _settingsMessage.value = "No notes found in that file."
            return@launch
        }
        val current = store.all()
        val merged = mergeImportNotes(current, incoming)
        val before = current.associateBy { it.id }
        val stamp = now()
        val changed = merged.filter { before[it.id] != it }.map { it.copy(updatedAt = stamp) }
        if (changed.isEmpty()) {
            _settingsMessage.value = "Already up to date — nothing to import."
            return@launch
        }
        store.putAll(changed)
        touch()
        _settingsMessage.value = if (changed.size == 1) {
            "Imported 1 note."
        } else {
            "Imported ${changed.size} notes."
        }
    }

    private fun refreshReminder(note: Note) {
        val at = note.reminderAt
        if (at != null && !note.deleted) {
            ReminderWorker.schedule(app, note.id, note.title, note.body, at, note.repeat)
        } else {
            ReminderWorker.cancel(app, note.id)
        }
    }

    fun togglePin(note: Note) = viewModelScope.launch {
        store.put(note.copy(pinned = !note.pinned, updatedAt = now()))
        touch()
    }

    fun toggleArchive(note: Note) = viewModelScope.launch {
        store.put(note.copy(archived = !note.archived, updatedAt = now()))
        touch()
    }

    fun moveToTrash(note: Note) = viewModelScope.launch {
        store.tombstone(note.id)
        ReminderWorker.cancel(app, note.id)
        touch()
    }

    fun restore(note: Note) = viewModelScope.launch {
        val updated = note.copy(deleted = false, updatedAt = now())
        store.put(updated)
        refreshReminder(updated)
        touch()
    }

    fun deleteForever(note: Note) = viewModelScope.launch {
        try {
            engine.deleteForever(note.id)
        } catch (e: Exception) {
            deleteError.value = true
        }
    }

    fun syncNow() = viewModelScope.launch { touch() }

    fun clearSettingsMessage() { _settingsMessage.value = null }

    /** Rename this phone (server + local label). Needs network. */
    fun renameDevice(name: String) = viewModelScope.launch {
        val api = api ?: return@launch
        val auth = auth ?: return@launch
        val id = auth.deviceId() ?: return@launch
        val trimmed = name.trim().take(120)
        if (trimmed.isEmpty()) {
            _settingsMessage.value = "Give this phone a name first."
            return@launch
        }
        _settingsBusy.value = true
        try {
            withContext(Dispatchers.IO) { api.rename(id, trimmed) }
            auth.saveDeviceName(trimmed)
            _settingsMessage.value = "Renamed to “$trimmed”."
        } catch (e: ApiException) {
            _settingsMessage.value = if (e.status == 404) "Phone not found — sign out and enroll again." else "Rename failed (${e.status}). Try again online."
        } catch (e: Exception) {
            _settingsMessage.value = "No connection. Try again when online."
        } finally {
            _settingsBusy.value = false
        }
    }

    /** Rotate this phone's credential; the old token dies immediately. */
    fun rotateDevice() = viewModelScope.launch {
        val api = api ?: return@launch
        val auth = auth ?: return@launch
        val id = auth.deviceId() ?: return@launch
        _settingsBusy.value = true
        try {
            val res = withContext(Dispatchers.IO) { api.rotate(id) }
            auth.saveToken(res.token)
            _settingsMessage.value = "New login issued for this phone."
            touch()
        } catch (e: ApiException) {
            _settingsMessage.value = if (e.status == 404) "Phone not found — sign out and enroll again." else "Rotation failed (${e.status}). Try again online."
        } catch (e: Exception) {
            _settingsMessage.value = "No connection. Try again when online."
        } finally {
            _settingsBusy.value = false
        }
    }

    /** Persist a finished drawing; the caller already appended the marker. */
    fun saveDrawing(id: String, strokes: List<DrawingStroke>) = viewModelScope.launch {
        store.createDrawing(id, strokes)
        touch()
    }

    private fun now() = System.currentTimeMillis()
}
