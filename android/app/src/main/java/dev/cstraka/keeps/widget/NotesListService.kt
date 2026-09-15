package dev.cstraka.keeps.widget

import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import dev.cstraka.keeps.KeepsApp
import dev.cstraka.keeps.MainActivity
import dev.cstraka.keeps.R
import dev.cstraka.keeps.sync.Note
import dev.cstraka.keeps.sync.widgetRowSnippet
import dev.cstraka.keeps.sync.widgetRowTitle
import dev.cstraka.keeps.sync.widgetRows
import kotlinx.coroutines.runBlocking

private const val MAX_ROWS = 20

/**
 * Collection factory for [NotesListWidget]. Reads the local Room mirror
 * on the binder thread (same process, no IPC to our own store) in NOTES
 * view order: live, unarchived, pinned first, newest first.
 */
class NotesListService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = Factory(applicationContext)
}

private class Factory(private val context: android.content.Context) : RemoteViewsService.RemoteViewsFactory {

    private var rows: List<Note> = emptyList()

    override fun onCreate() = Unit

    override fun onDataSetChanged() {
        val app = context.applicationContext as? KeepsApp ?: return
        rows = widgetRows(runBlocking { app.localStore.all() }, MAX_ROWS)
    }

    override fun onDestroy() {
        rows = emptyList()
    }

    override fun getCount(): Int = rows.size

    override fun getViewAt(position: Int): RemoteViews {
        val note = rows.getOrNull(position) ?: return loading()
        val views = RemoteViews(context.packageName, R.layout.widget_note_row)
        val snippet = widgetRowSnippet(note)
        views.setTextViewText(R.id.row_title, widgetRowTitle(note))
        views.setTextViewText(R.id.row_snippet, snippet)
        views.setViewVisibility(
            R.id.row_snippet,
            if (snippet.isNotBlank()) android.view.View.VISIBLE else android.view.View.GONE,
        )
        val fillIn = Intent().putExtra(MainActivity.EXTRA_NOTE_ID, note.id)
        views.setOnClickFillInIntent(R.id.note_row, fillIn)
        return views
    }

    private fun loading(): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_note_row)

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long =
        rows.getOrNull(position)?.id?.hashCode()?.toLong() ?: position.toLong()

    override fun hasStableIds(): Boolean = true
}
