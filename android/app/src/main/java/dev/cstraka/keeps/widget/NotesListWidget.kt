package dev.cstraka.keeps.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import dev.cstraka.keeps.MainActivity
import dev.cstraka.keeps.R

/**
 * Notes-list widget: the newest live notes (NOTES view order) with one
 * tap per row opening that note in the editor. Refreshes whenever the
 * app writes (see [refreshAll], called from the ViewModel touch path)
 * and on every host update; no polling interval.
 */
class NotesListWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) updateOne(context, manager, id)
    }

    companion object {
        fun updateOne(context: Context, manager: AppWidgetManager, appWidgetId: Int) {
            val service = Intent(context, NotesListService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                // Distinct URI per widget so the host does not share one factory.
                data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            }
            val views = RemoteViews(context.packageName, R.layout.widget_notes_list)
            views.setRemoteAdapter(R.id.notes_list, service)
            views.setEmptyView(R.id.notes_list, R.id.notes_empty)
            // Header opens the app; rows fill in their own note id.
            val open = Intent(context, MainActivity::class.java)
            val openPending = PendingIntent.getActivity(
                context, appWidgetId, open,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.notes_header, openPending)
            val template = Intent(context, MainActivity::class.java)
                .setAction(QuickCaptureWidget.ACTION_COMPOSE)
                .putExtra(MainActivity.EXTRA_COMPOSE, true)
            val rowPending = PendingIntent.getActivity(
                context, appWidgetId, template,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            views.setPendingIntentTemplate(R.id.notes_list, rowPending)
            manager.updateAppWidget(appWidgetId, views)
        }

        /** Rebind every list widget after local writes. Cheap IPC. */
        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, NotesListWidget::class.java),
            )
            if (ids.isEmpty()) return
            manager.notifyAppWidgetViewDataChanged(ids, R.id.notes_list)
            for (id in ids) updateOne(context, manager, id)
        }
    }
}
