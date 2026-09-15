package dev.cstraka.keeps.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import dev.cstraka.keeps.MainActivity
import dev.cstraka.keeps.R

/**
 * Quick-capture widget: one tap opens the configured target — a blank
 * composer by default, or a chosen note (see [WidgetConfigActivity]).
 */
class QuickCaptureWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) updateOne(context, id)
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        for (id in ids) WidgetConfigActivity.forget(context, id)
    }

    companion object {
        const val ACTION_COMPOSE = "dev.cstraka.keeps.action.COMPOSE"

        fun updateOne(context: Context, appWidgetId: Int) {
            val manager = AppWidgetManager.getInstance(context)
            val target = WidgetConfigActivity.targetFor(context, appWidgetId)
            val launch = Intent(context, MainActivity::class.java)
                .setAction(ACTION_COMPOSE)
                .putExtra(MainActivity.EXTRA_COMPOSE, true)
            if (target != null) launch.putExtra(MainActivity.EXTRA_NOTE_ID, target)
            val pending = PendingIntent.getActivity(
                context, appWidgetId, launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val views = RemoteViews(context.packageName, R.layout.widget_quick_capture)
            views.setOnClickPendingIntent(R.id.widget_new_note, pending)
            manager.updateAppWidget(appWidgetId, views)
        }
    }
}
