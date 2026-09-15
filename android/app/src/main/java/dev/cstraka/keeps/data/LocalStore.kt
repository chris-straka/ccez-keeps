package dev.cstraka.keeps.data

import android.content.SharedPreferences
import androidx.core.content.edit
import dev.cstraka.keeps.sync.Drawing
import dev.cstraka.keeps.sync.DrawingStroke
import dev.cstraka.keeps.sync.Label
import dev.cstraka.keeps.sync.Note
import dev.cstraka.keeps.sync.newDrawing
import dev.cstraka.keeps.sync.newLabel
import dev.cstraka.keeps.sync.newNote
import java.util.UUID

/**
 * Local-first store: Room owns the notes, plain prefs own the two sync
 * watermarks (mirroring web/store sync.ts). Cursor = server seq watermark;
 * pushMark = max updatedAt pushed. Kept separate so an unpushed edit can
 * never hide below the pull cursor across restarts.
 */
class LocalStore(
    private val dao: NoteDao,
    private val drawings: DrawingDao,
    private val labels: LabelDao,
    private val meta: SharedPreferences,
) {
    fun observeAll() = dao.observeAll()

    suspend fun all(): List<Note> = dao.all().map { it.toNote() }

    suspend fun put(note: Note) = dao.upsert(note.toEntity())

    suspend fun noteById(id: String): Note? = dao.byId(id)?.toNote()

    suspend fun putAll(notes: List<Note>) = dao.upsertAll(notes.map { it.toEntity() })

    suspend fun create(
        title: String,
        body: String,
        labelIds: List<String> = emptyList(),
        reminderAt: Long? = null,
        repeat: String? = null,
    ): Note {
        val note = newNote(
            id = UUID.randomUUID().toString(), title = title, body = body,
            labelIds = labelIds, reminderAt = reminderAt, repeat = repeat,
        )
        put(note)
        return note
    }

    suspend fun tombstone(id: String) {
        val current = dao.all().firstOrNull { it.id == id }?.toNote() ?: return
        put(current.copy(deleted = true, updatedAt = System.currentTimeMillis()))
    }

    suspend fun dropLocal(id: String) = dao.dropLocal(id)

    fun getCursor(): Long = meta.getLong("cursor", 0L)

    fun setCursor(value: Long) = meta.edit { putLong("cursor", value) }

    fun getPushMark(): Long = meta.getLong("push_mark", 0L)

    fun setPushMark(value: Long) = meta.edit { putLong("push_mark", value) }

    // Drawings lane: own watermarks, same discipline as notes.
    fun observeDrawings() = drawings.observeAll()

    suspend fun allDrawings(): List<Drawing> = drawings.all().map { it.toDrawing() }

    suspend fun drawingById(id: String): Drawing? = drawings.byId(id)?.toDrawing()

    suspend fun putDrawing(drawing: Drawing) = drawings.upsert(drawing.toEntity())

    suspend fun putAllDrawings(rows: List<Drawing>) = drawings.upsertAll(rows.map { it.toEntity() })

    suspend fun createDrawing(id: String, strokes: List<DrawingStroke>): Drawing {
        val drawing = newDrawing(id = id, strokes = strokes)
        putDrawing(drawing)
        return drawing
    }

    suspend fun tombstoneDrawing(id: String) {
        val current = drawings.byId(id)?.toDrawing() ?: return
        putDrawing(current.copy(deleted = true, updatedAt = System.currentTimeMillis()))
    }

    suspend fun dropLocalDrawing(id: String) = drawings.dropLocal(id)

    fun getDrawingsCursor(): Long = meta.getLong("drawings_cursor", 0L)

    fun setDrawingsCursor(value: Long) = meta.edit { putLong("drawings_cursor", value) }

    fun getDrawingsPushMark(): Long = meta.getLong("drawings_push_mark", 0L)

    fun setDrawingsPushMark(value: Long) = meta.edit { putLong("drawings_push_mark", value) }

    // Labels lane: own watermarks, same discipline as notes and drawings.
    fun observeLabels() = labels.observeAll()

    suspend fun allLabels(): List<Label> = labels.all().map { it.toLabel() }

    suspend fun labelById(id: String): Label? = labels.byId(id)?.toLabel()

    suspend fun putLabel(label: Label) = labels.upsert(label.toEntity())

    suspend fun putAllLabels(rows: List<Label>) = labels.upsertAll(rows.map { it.toEntity() })

    suspend fun createLabel(name: String): Label {
        val label = newLabel(id = UUID.randomUUID().toString(), name = name)
        putLabel(label)
        return label
    }

    suspend fun tombstoneLabel(id: String) {
        val current = labels.byId(id)?.toLabel() ?: return
        putLabel(current.copy(deleted = true, updatedAt = System.currentTimeMillis()))
    }

    suspend fun dropLocalLabel(id: String) = labels.dropLocal(id)

    fun getLabelsCursor(): Long = meta.getLong("labels_cursor", 0L)

    fun setLabelsCursor(value: Long) = meta.edit { putLong("labels_cursor", value) }

    fun getLabelsPushMark(): Long = meta.getLong("labels_push_mark", 0L)

    fun setLabelsPushMark(value: Long) = meta.edit { putLong("labels_push_mark", value) }
}
