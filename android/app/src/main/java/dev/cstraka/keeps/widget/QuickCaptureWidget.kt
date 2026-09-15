package dev.cstraka.keeps.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import dev.cstraka.keeps.MainActivity
import dev.cstraka.keeps.R

/** Quick-capture widget: a single "New note" button that opens the composer. */
class QuickCaptureWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) {
            val launch = Intent(context, MainActivity::class.java)
                .setAction(ACTION_COMPOSE)
                .putExtra(MainActivity.EXTRA_COMPOSE, true)
            val pending = PendingIntent.getActivity(
                context, id, launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val views = RemoteViews(context.packageName, R.layout.widget_quick_capture)
            views.setOnClickPendingIntent(R.id.widget_new_note, pending)
            manager.updateAppWidget(id, views)
        }
    }

    companion object {
        private const val ACTION_COMPOSE = "dev.cstraka.keeps.action.COMPOSE"
    }
}
