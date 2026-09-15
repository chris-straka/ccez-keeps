package dev.cstraka.keeps.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import dev.cstraka.keeps.KeepsApp
import dev.cstraka.keeps.sync.Note
import kotlinx.coroutines.launch

/**
 * Widget tap target picker. Shown when the widget is placed (and
 * re-runnable from any widget host's configure flow): either a blank
 * composer or one chosen note opened in the editor. The choice is stored
 * per widget id so several widgets can point at different notes.
 */
class WidgetConfigActivity : ComponentActivity() {

    private var notes by mutableStateOf<List<Note>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        val app = application as KeepsApp
        lifecycleScope.launch {
            notes = app.localStore.all()
                .filter { !it.deleted }
                .sortedByDescending { it.updatedAt }
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ConfigList(
                        notes = notes,
                        current = targetFor(this, appWidgetId),
                        onPick = { noteId ->
                            saveTarget(appWidgetId, noteId)
                            QuickCaptureWidget.updateOne(this, appWidgetId)
                            setResult(RESULT_OK, Intent().putExtra(
                                AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId,
                            ))
                            finish()
                        },
                        onCancel = { finish() },
                    )
                }
            }
        }
    }

    private fun saveTarget(appWidgetId: Int, noteId: String?) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(keyFor(appWidgetId), noteId)
            .apply()
    }

    companion object {
        private const val PREFS = "widget_targets"

        fun keyFor(appWidgetId: Int): String = "target_$appWidgetId"

        /** Null/blank = blank composer; otherwise the note id to open. */
        fun targetFor(context: Context, appWidgetId: Int): String? =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(keyFor(appWidgetId), null)
                .takeUnless { it.isNullOrBlank() }

        fun forget(context: Context, appWidgetId: Int) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove(keyFor(appWidgetId))
                .apply()
        }
    }
}

@Composable
private fun ConfigList(
    notes: List<Note>,
    current: String?,
    onPick: (String?) -> Unit,
    onCancel: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Widget opens…", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.weight(1f)) {
            item {
                TargetRow(
                    label = "Blank note",
                    selected = current == null,
                    onClick = { onPick(null) },
                )
            }
            items(notes, key = { it.id }) { note ->
                TargetRow(
                    label = note.title.ifBlank { note.body.lineSequence().firstOrNull().orEmpty() }
                        .ifBlank { "Untitled" },
                    selected = current == note.id,
                    onClick = { onPick(note.id) },
                )
            }
        }
        TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.End)) {
            Text("Cancel")
        }
    }
}

@Composable
private fun TargetRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(8.dp))
        Text(label, maxLines = 1)
    }
}
