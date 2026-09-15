package dev.cstraka.keeps.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dev.cstraka.keeps.data.LocalStore
import dev.cstraka.keeps.sync.Drawing
import dev.cstraka.keeps.sync.Label
import dev.cstraka.keeps.sync.Note
import dev.cstraka.keeps.sync.mergeDrawingLists
import dev.cstraka.keeps.sync.mergeLabelLists
import dev.cstraka.keeps.sync.mergeNoteLists
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface SyncStatus {
    data object Idle : SyncStatus
    data object Syncing : SyncStatus
    data object Offline : SyncStatus
    data class Error(val message: String) : SyncStatus
    /** 401 from the Worker: token revoked or unknown — re-enroll. */
    data object NeedsLogin : SyncStatus
}

/**
 * Push-then-pull engine mirroring web/store/sync.ts: debounced by the
 * caller (ViewModel schedules WorkManager), quiescent loop so an edit
 * landing mid-flight is never stranded below the watermarks.
 */
class SyncEngine(
    private val context: Context,
    private val store: LocalStore,
    private val api: KeepsApi,
) {
    private val mutex = Mutex()
    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val status: StateFlow<SyncStatus> = _status

    fun isOnline(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** Immediate push-then-pull. Concurrent callers share one round. */
    suspend fun flush() {
        mutex.withLock { round() }
    }

    private suspend fun round() {
        if (!isOnline()) {
            _status.value = SyncStatus.Offline
            return
        }
        _status.value = SyncStatus.Syncing
        try {
            // Loop until quiescent: an edit landing mid-flight must not be
            // stranded below the watermarks.
            var guard = 0
            do {
                push()
                pull()
                pushDrawings()
                pullDrawings()
                pushLabels()
                pullLabels()
                guard++
            } while (needsPush() && guard < 5)
            _status.value = SyncStatus.Idle
        } catch (e: ApiException) {
            _status.value = if (e.status == 401) SyncStatus.NeedsLogin
            else SyncStatus.Error("sync failed: ${e.status}")
        } catch (e: Exception) {
            _status.value = if (isOnline()) SyncStatus.Error("sync failed") else SyncStatus.Offline
        }
    }

    private suspend fun needsPush(): Boolean {
        val mark = store.getPushMark()
        if (store.all().any { it.updatedAt > mark }) return true
        val drawMark = store.getDrawingsPushMark()
        if (store.allDrawings().any { it.updatedAt > drawMark }) return true
        val labelMark = store.getLabelsPushMark()
        return store.allLabels().any { it.updatedAt > labelMark }
    }

    private suspend fun push() {
        val mark = store.getPushMark()
        val dirty = store.all().filter { it.updatedAt > mark }
        if (dirty.isEmpty()) return
        val since = store.getCursor()
        val res = api.push(dirty, since)
        applyDeltas(res.deltas)
        store.setCursor(res.cursor)
        val maxDirty = dirty.maxOf { it.updatedAt }
        store.setPushMark(maxOf(mark, maxDirty))
    }

    suspend fun pull() {
        val cursor = store.getCursor()
        val res = api.pull(cursor)
        applyDeltas(res.notes)
        store.setCursor(res.cursor)
    }

    private suspend fun applyDeltas(deltas: List<Note>) {
        if (deltas.isEmpty()) return
        val local = store.all()
        val merged = mergeNoteLists(local, deltas)
        val current = local.associateBy { it.id }
        // put() notifies Room observers; unchanged rows stay silent.
        val written = merged.filter { current[it.id] != it }
        for (note in written) store.put(note)
        // Reminders set on another client arrive as plain rows: reconcile
        // their firings here, since nothing else schedules pulled notes.
        val plan = planReminders(written, System.currentTimeMillis())
        for (note in plan.schedule) {
            ReminderWorker.schedule(
                context, note.id, note.title, note.body, note.reminderAt!!, note.repeat,
            )
        }
        for (id in plan.cancel) ReminderWorker.cancel(context, id)
    }

    /**
     * Push the tombstone, then hard-drop the local row. Throws when offline
     * so the note stays safely in Trash instead of vanishing unsynced.
     */
    suspend fun deleteForever(id: String) {
        if (!isOnline()) throw IllegalStateException("deleteForever: offline")
        store.tombstone(id)
        flush()
        store.dropLocal(id)
    }

    private suspend fun pushDrawings() {
        val mark = store.getDrawingsPushMark()
        val dirty = store.allDrawings().filter { it.updatedAt > mark }
        if (dirty.isEmpty()) return
        val since = store.getDrawingsCursor()
        val res = api.pushDrawings(dirty, since)
        applyDrawingDeltas(res.deltas)
        store.setDrawingsCursor(res.cursor)
        val maxDirty = dirty.maxOf { it.updatedAt }
        store.setDrawingsPushMark(maxOf(mark, maxDirty))
    }

    suspend fun pullDrawings() {
        val cursor = store.getDrawingsCursor()
        val res = api.pullDrawings(cursor)
        applyDrawingDeltas(res.drawings)
        store.setDrawingsCursor(res.cursor)
    }

    private suspend fun applyDrawingDeltas(deltas: List<Drawing>) {
        if (deltas.isEmpty()) return
        val local = store.allDrawings()
        val merged = mergeDrawingLists(local, deltas)
        val current = local.associateBy { it.id }
        for (drawing in merged) {
            if (current[drawing.id] != drawing) store.putDrawing(drawing)
        }
    }

    suspend fun deleteDrawingForever(id: String) {
        if (!isOnline()) throw IllegalStateException("deleteDrawingForever: offline")
        store.tombstoneDrawing(id)
        flush()
        store.dropLocalDrawing(id)
    }

    private suspend fun pushLabels() {
        val mark = store.getLabelsPushMark()
        val dirty = store.allLabels().filter { it.updatedAt > mark }
        if (dirty.isEmpty()) return
        val since = store.getLabelsCursor()
        val res = api.pushLabels(dirty, since)
        applyLabelDeltas(res.deltas)
        store.setLabelsCursor(res.cursor)
        val maxDirty = dirty.maxOf { it.updatedAt }
        store.setLabelsPushMark(maxOf(mark, maxDirty))
    }

    suspend fun pullLabels() {
        val cursor = store.getLabelsCursor()
        val res = api.pullLabels(cursor)
        applyLabelDeltas(res.labels)
        store.setLabelsCursor(res.cursor)
    }

    private suspend fun applyLabelDeltas(deltas: List<Label>) {
        if (deltas.isEmpty()) return
        val local = store.allLabels()
        val merged = mergeLabelLists(local, deltas)
        val current = local.associateBy { it.id }
        for (label in merged) {
            if (current[label.id] != label) store.putLabel(label)
        }
    }

    suspend fun deleteLabelForever(id: String) {
        if (!isOnline()) throw IllegalStateException("deleteLabelForever: offline")
        store.tombstoneLabel(id)
        flush()
        store.dropLocalLabel(id)
    }
}
