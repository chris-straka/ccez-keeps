package dev.cstraka.keeps.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.InputChip
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.cstraka.keeps.data.attachmentBitmap
import dev.cstraka.keeps.data.processImageBytes
import dev.cstraka.keeps.sync.Attachment
import dev.cstraka.keeps.sync.ChecklistItem
import dev.cstraka.keeps.sync.DrawingStroke
import dev.cstraka.keeps.sync.Label
import dev.cstraka.keeps.sync.Note
import dev.cstraka.keeps.sync.NoteLimits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

// Public for the drawing round-trip instrumented test; the app only uses
// it through NotesScreen.
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NoteDialog(
    title: String,
    initialTitle: String,
    initialBody: String,
    initialColor: String,
    dark: Boolean = true,
    initialLabelIds: List<String> = emptyList(),
    initialReminderAt: Long? = null,
    initialRepeat: String? = null,
    initialChecklist: List<ChecklistItem>? = null,
    initialAttachments: List<Attachment> = emptyList(),
    /** Open with the caret at the end of the body (a tap on an existing
     * note's body) instead of in the title (a new note, or a title tap). */
    startInBody: Boolean = false,
    allLabels: List<Label> = emptyList(),
    onCreateLabel: (String, (Label) -> Unit) -> Unit = { _, _ -> },
    /** Composer opened from the Reminders filter starts with the date picker open. */
    startWithReminderPicker: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (String, String, String, List<String>, Long?, String?, List<ChecklistItem>?, List<Attachment>) -> Unit,
    onSaveDrawing: (String, List<DrawingStroke>) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val context = LocalContext.current
    val focusRequester = remember { FocusRequester() }
    val bodyFocus = remember { FocusRequester() }
    var t by remember { mutableStateOf(initialTitle) }
    var b by remember { mutableStateOf(TextFieldValue(initialBody, TextRange(initialBody.length))) }
    var c by remember { mutableStateOf(initialColor) }
    val selIds = remember { mutableStateListOf(*initialLabelIds.toTypedArray()) }
    var reminder by remember { mutableStateOf(initialReminderAt) }
    var repeatSel by remember { mutableStateOf(initialRepeat) }
    var labelPickerOpen by remember { mutableStateOf(false) }
    var newLabelName by remember { mutableStateOf("") }
    var pendingSelect by remember { mutableStateOf<String?>(null) }
    var showDate by remember { mutableStateOf(startWithReminderPicker) }
    var showTime by remember { mutableStateOf(false) }
    var pendingDate by remember { mutableStateOf(0L) }
    // Structured checklist rows; null = plain text body mode.
    var items by remember { mutableStateOf(initialChecklist?.map { it.copy() }) }
    // Attachments staged for save (start from the opened note's).
    val staged = remember { mutableStateListOf(*initialAttachments.toTypedArray()) }
    var attachError by remember { mutableStateOf<String?>(null) }
    var viewing by remember { mutableStateOf<Attachment?>(null) }
    val scope = rememberCoroutineScope()
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(NoteLimits.MAX_ATTACHMENTS),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        attachError = null
        scope.launch(Dispatchers.IO) {
            val fresh = mutableListOf<Attachment>()
            for (uri in uris) {
                if (staged.size + fresh.size >= NoteLimits.MAX_ATTACHMENTS) break
                val name = context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (c.moveToFirst() && idx >= 0) c.getString(idx) else null
                } ?: "photo.jpg"
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: continue
                processImageBytes(bytes, name)?.let { fresh.add(it) }
            }
            withContext(Dispatchers.Main) {
                if (fresh.isEmpty()) {
                    attachError = "Those photos could not be read or are too large."
                } else {
                    staged.addAll(fresh)
                }
            }
        }
    }
    // toChecklistMode + save helpers live below toggleList (they use pushHist).
    // POST_NOTIFICATIONS is asked at reminder-set time; a denial only
    // drops the firing, the in-app overdue state stays.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    fun ensureReminderPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    // A label created from the picker selects itself once it syncs in.
    LaunchedEffect(allLabels) {
        pendingSelect?.let { name ->
            allLabels.firstOrNull { it.name == name }?.let {
                if (!selIds.contains(it.id)) selIds.add(it.id)
                pendingSelect = null
            }
        }
    }
    // Best effort: headless harnesses may have nothing to take focus,
    // and an empty checklist has no first row to take the body's.
    fun focusBody() {
        try {
            bodyFocus.requestFocus()
        } catch (_: IllegalStateException) {
        }
    }
    // Undo history over body text (mirrors the web editor's snapshot
    // history; title stays a plain field on both clients).
    val history = remember { mutableListOf(initialBody) }
    var histIdx by remember { mutableStateOf(0) }
    fun pushHist(text: String) {
        while (history.size > histIdx + 1) history.removeAt(history.size - 1)
        if (history[histIdx] != text) {
            history.add(text)
            while (history.size > 100) history.removeAt(0)
            histIdx = history.size - 1
        }
    }
    fun undo() {
        if (histIdx > 0) {
            histIdx--
            val text = history[histIdx]
            b = TextFieldValue(text, TextRange(text.length))
        }
    }
    fun redo() {
        if (histIdx < history.size - 1) {
            histIdx++
            val text = history[histIdx]
            b = TextFieldValue(text, TextRange(text.length))
        }
    }
    fun wrapSelection(before: String, after: String) {
        val start = b.selection.start
        val end = b.selection.end
        val selected = b.text.substring(start, end)
        val out = b.text.substring(0, start) + before + selected + after + b.text.substring(end)
        b = if (selected.isEmpty()) {
            TextFieldValue(out, TextRange(start + before.length))
        } else {
            TextFieldValue(out, TextRange(start, start + before.length + selected.length + after.length))
        }
        pushHist(out)
    }
    var drawOpen by remember { mutableStateOf(false) }
    fun insertDrawing(id: String, strokes: List<DrawingStroke>) {
        onSaveDrawing(id, strokes)
        val cur = b.text
        val prefix = if (cur.isEmpty() || cur.endsWith("\n")) cur else "$cur\n"
        val out = "$prefix![drawing]($id)"
        b = TextFieldValue(out, TextRange(out.length))
        pushHist(out)
    }
    fun toggleList() {
        val text = b.text
        val caret = b.selection.start
        val ls = text.lastIndexOf('\n', caret - 1) + 1
        val le = text.indexOf('\n', caret).let { if (it == -1) text.length else it }
        val line = text.substring(ls, le)
        val next = when {
            line.startsWith("- [ ] ") -> "- [x] " + line.removePrefix("- [ ] ")
            line.startsWith("- [x] ") -> "- [ ] " + line.removePrefix("- [x] ")
            else -> "- [ ] $line"
        }
        val out = text.substring(0, ls) + next + text.substring(le)
        b = TextFieldValue(out, TextRange(ls + next.length))
        pushHist(out)
    }
    fun toChecklistMode() {
        if (items != null) {
            val body = items.orEmpty().joinToString("\n") { it.text }
            items = null
            b = TextFieldValue(body, TextRange(body.length))
            pushHist(body)
            return
        }
        val lines = b.text.split("\n")
            .map { it.replace(Regex("^\\s*-\\s*\\[( |x)\\]\\s*"), "").trimEnd() }
        val kept = lines.filter { it.isNotBlank() }
        items = (if (kept.isNotEmpty()) kept else listOf("")).map {
            ChecklistItem(id = UUID.randomUUID().toString(), text = it)
        }
    }
    fun checklistForSave(): List<ChecklistItem>? =
        items?.filter { it.text.isNotBlank() }
            ?.take(NoteLimits.MAX_CHECKLIST_ITEMS)
            ?.map { it.copy(text = it.text.take(NoteLimits.MAX_CHECKLIST_TEXT)) }
    fun bodyForSave(): String =
        checklistForSave()?.joinToString("\n") { it.text } ?: b.text
    // Back / tap-outside keep the draft like Keep does; only Cancel discards.
    // Unchanged notes just close, so reopening never bumps updatedAt.
    fun closeKeeping() {
        val changed = t != initialTitle || bodyForSave() != initialBody ||
            c != initialColor || selIds.toList() != initialLabelIds ||
            reminder != initialReminderAt || repeatSel != initialRepeat ||
            checklistForSave() != initialChecklist || staged.toList() != initialAttachments
        if (changed) {
            onConfirm(
                t, bodyForSave(), c, selIds.toList(), reminder, repeatSel,
                checklistForSave(), staged.toList(),
            )
        } else {
            onDismiss()
        }
    }
    // Keep-style sheet: the card fills the window and takes the note's
    // colour; title and body are borderless; labels and the reminder are
    // chips; one footer row of icon tools, then Cancel and a filled Save.
    // decorFitsSystemWindows=false + safeDrawing padding: the sheet
    // shrinks above the keyboard, so the caret scrolls inside the middle
    // instead of sliding under the IME.
    var colorOpen by remember { mutableStateOf(false) }
    var addOpen by remember { mutableStateOf(false) }
    val cardColor = if (c == "default") MaterialTheme.colorScheme.surface else noteColor(c, dark)
    val plainField = TextFieldDefaults.colors(
        focusedContainerColor = Color.Transparent,
        unfocusedContainerColor = Color.Transparent,
        disabledContainerColor = Color.Transparent,
        focusedIndicatorColor = Color.Transparent,
        unfocusedIndicatorColor = Color.Transparent,
        disabledIndicatorColor = Color.Transparent,
    )

    @Composable
    fun RowScope.Tool(
        icon: ImageVector,
        label: String,
        enabled: Boolean = true,
        onClick: () -> Unit,
    ) {
        IconButton(
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                onClick()
            },
            enabled = enabled,
            modifier = Modifier.weight(1f).height(44.dp),
        ) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(22.dp))
        }
    }

    Dialog(
        onDismissRequest = ::closeKeeping,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier.fillMaxSize()
                .background(Color.Black.copy(alpha = 0.7f))
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 10.dp, vertical = 10.dp),
        ) {
            // Runs in the dialog's own composition: from the caller's,
            // it fired before the fields were attached and focused nothing.
            LaunchedEffect(Unit) {
                try {
                    if (startInBody && (initialChecklist == null || initialChecklist.isNotEmpty())) {
                        bodyFocus.requestFocus()
                    } else {
                        focusRequester.requestFocus()
                    }
                } catch (_: IllegalStateException) {
                }
            }
            Card(
                colors = CardDefaults.cardColors(containerColor = cardColor),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxSize().testTag("editorCard"),
            ) {
                Column(Modifier.fillMaxSize()) {
                    Text(
                        title,
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.Gray,
                        modifier = Modifier.padding(start = 20.dp, top = 12.dp),
                    )
                    Column(
                        modifier = Modifier.weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 8.dp),
                    ) {
                        if (staged.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(horizontal = 12.dp),
                            ) {
                                for (a in staged.toList()) {
                                    val thumb = remember(a.thumbUrl) {
                                        attachmentBitmap(a.thumbUrl)?.asImageBitmap()
                                    }
                                    Box(contentAlignment = Alignment.TopEnd) {
                                        if (thumb != null) {
                                            Image(
                                                bitmap = thumb,
                                                contentDescription = a.name,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.size(96.dp)
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .clickable { viewing = a },
                                            )
                                        } else {
                                            Text(a.name, fontSize = 11.sp)
                                        }
                                        IconButton(
                                            onClick = { staged.remove(a) },
                                            modifier = Modifier.size(28.dp),
                                        ) {
                                            Icon(
                                                Icons.Filled.Close,
                                                contentDescription = "Remove ${a.name}",
                                                modifier = Modifier.size(16.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        attachError?.let { err ->
                            Text(
                                err, fontSize = 12.sp, color = Color(0xFFE5534B),
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            )
                        }
                        TextField(
                            value = t,
                            onValueChange = { t = it },
                            placeholder = { Text("Title", style = MaterialTheme.typography.titleLarge) },
                            textStyle = MaterialTheme.typography.titleLarge,
                            singleLine = true,
                            // Enter moves on to the body, as on the web.
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                            keyboardActions = KeyboardActions(onNext = { focusBody() }),
                            colors = plainField,
                            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester)
                                .testTag("editorTitle"),
                        )
                        val rows = items
                        if (rows == null) {
                            TextField(
                                value = b,
                                onValueChange = { b = it; pushHist(it.text) },
                                placeholder = { Text("Note") },
                                minLines = 4,
                                colors = plainField,
                                modifier = Modifier.fillMaxWidth().focusRequester(bodyFocus)
                                    .testTag("editorBody"),
                            )
                        } else {
                            Column {
                                for (item in rows) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(
                                            checked = item.checked,
                                            onCheckedChange = { checked ->
                                                items = rows.map {
                                                    if (it.id == item.id) it.copy(checked = checked) else it
                                                }
                                            },
                                        )
                                        TextField(
                                            value = item.text,
                                            onValueChange = { text ->
                                                items = rows.map {
                                                    if (it.id == item.id) it.copy(text = text) else it
                                                }
                                            },
                                            placeholder = { Text("List item") },
                                            singleLine = true,
                                            colors = plainField,
                                            modifier = Modifier.weight(1f).then(
                                                if (item.id == rows.first().id) {
                                                    Modifier.focusRequester(bodyFocus)
                                                } else {
                                                    Modifier
                                                },
                                            ),
                                        )
                                        IconButton(onClick = {
                                            items = rows.filter { it.id != item.id }
                                        }) {
                                            Icon(Icons.Filled.Close, contentDescription = "Delete item")
                                        }
                                    }
                                }
                                TextButton(
                                    onClick = {
                                        if (rows.size < NoteLimits.MAX_CHECKLIST_ITEMS) {
                                            items = rows + ChecklistItem(
                                                id = UUID.randomUUID().toString(), text = "",
                                            )
                                        }
                                    },
                                    enabled = rows.size < NoteLimits.MAX_CHECKLIST_ITEMS,
                                ) { Text("+ Add item") }
                            }
                        }
                        // Chips: labels, then the reminder with its repeat.
                        val byId = allLabels.associateBy { it.id }
                        if (selIds.isNotEmpty() || reminder != null) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.padding(horizontal = 12.dp),
                            ) {
                                for (id in selIds.toList()) {
                                    val name = byId[id]?.name ?: "label"
                                    InputChip(
                                        selected = false,
                                        onClick = { selIds.remove(id) },
                                        label = { Text(name) },
                                        trailingIcon = {
                                            Icon(
                                                Icons.Filled.Close,
                                                contentDescription = "Remove label $name",
                                                modifier = Modifier.size(16.dp),
                                            )
                                        },
                                    )
                                }
                                reminder?.let { at ->
                                    InputChip(
                                        selected = true,
                                        onClick = { showDate = true },
                                        label = {
                                            Text(
                                                formatReminder(at) + when (repeatSel) {
                                                    "daily" -> " · Daily"
                                                    "weekly" -> " · Weekly"
                                                    else -> ""
                                                },
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Filled.Notifications,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp),
                                            )
                                        },
                                        trailingIcon = {
                                            Icon(
                                                Icons.Filled.Close,
                                                contentDescription = "Clear reminder",
                                                modifier = Modifier.size(16.dp).clickable {
                                                    reminder = null
                                                    repeatSel = null
                                                },
                                            )
                                        },
                                    )
                                }
                            }
                        }
                        if (reminder != null) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.padding(horizontal = 12.dp),
                            ) {
                                Text("Repeats", style = MaterialTheme.typography.bodySmall)
                                for ((label, value) in listOf("Never" to null, "Daily" to "daily", "Weekly" to "weekly")) {
                                    FilterChip(
                                        selected = repeatSel == value,
                                        onClick = {
                                            haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                                            repeatSel = value
                                        },
                                        label = { Text(label) },
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    // Panels above the toolbar: colour swatches, label picker.
                    AnimatedVisibility(visible = colorOpen) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            for (key in COLOR_KEYS) {
                                val selected = c == key
                                Box(
                                    Modifier.size(32.dp)
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(noteColor(key, dark))
                                        .border(
                                            if (selected) 2.dp else 1.dp,
                                            if (selected) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.5f),
                                            RoundedCornerShape(16.dp),
                                        )
                                        .clickable {
                                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            c = key
                                        }
                                        .semantics { contentDescription = "Colour $key" }
                                        .testTag("color-$key"),
                                )
                            }
                        }
                    }
                    AnimatedVisibility(visible = labelPickerOpen) {
                        Column(Modifier.padding(horizontal = 12.dp)) {
                            for (lab in allLabels) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(
                                        checked = selIds.contains(lab.id),
                                        onCheckedChange = { checked ->
                                            if (checked) {
                                                if (!selIds.contains(lab.id)) selIds.add(lab.id)
                                            } else {
                                                selIds.remove(lab.id)
                                            }
                                        },
                                    )
                                    Text(lab.name)
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = newLabelName,
                                    onValueChange = { newLabelName = it },
                                    label = { Text("New label") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                )
                                Spacer(Modifier.width(8.dp))
                                TextButton(onClick = {
                                    val name = newLabelName.trim()
                                    if (name.isNotEmpty()) {
                                        pendingSelect = name
                                        newLabelName = ""
                                        onCreateLabel(name) { created ->
                                            if (!selIds.contains(created.id)) {
                                                selIds.add(created.id)
                                            }
                                            pendingSelect = null
                                        }
                                    }
                                }) { Text("Add") }
                            }
                        }
                    }
                    // Tool row: add menu (reminder, labels), then icons.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    ) {
                        Box(Modifier.weight(1f)) {
                            IconButton(
                                onClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                                    addOpen = true
                                },
                                modifier = Modifier.fillMaxWidth().height(44.dp),
                            ) {
                                Icon(Icons.Filled.Add, contentDescription = "Add", modifier = Modifier.size(22.dp))
                            }
                            DropdownMenu(expanded = addOpen, onDismissRequest = { addOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text(if (reminder == null) "Reminder" else "Change reminder") },
                                    leadingIcon = { Icon(Icons.Filled.Notifications, contentDescription = null) },
                                    onClick = {
                                        addOpen = false
                                        showDate = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Labels") },
                                    leadingIcon = { Icon(Icons.Filled.Label, contentDescription = null) },
                                    onClick = {
                                        addOpen = false
                                        colorOpen = false
                                        labelPickerOpen = !labelPickerOpen
                                    },
                                )
                            }
                        }
                        Tool(Icons.Filled.Palette, "Colour") {
                            labelPickerOpen = false
                            colorOpen = !colorOpen
                        }
                        Tool(Icons.Filled.Image, "Photo") {
                            if (staged.size < NoteLimits.MAX_ATTACHMENTS) {
                                photoPicker.launch(
                                    PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly,
                                    ),
                                )
                            } else {
                                attachError = "At most ${NoteLimits.MAX_ATTACHMENTS} photos per note."
                            }
                        }
                        Tool(Icons.Filled.Draw, "Draw") { drawOpen = true }
                        Tool(Icons.Filled.Checklist, "Checklist") { toChecklistMode() }
                        Tool(Icons.Filled.CheckBox, "Checkbox on this line", enabled = items == null) { toggleList() }
                        Tool(Icons.Filled.FormatBold, "Bold", enabled = items == null) { wrapSelection("**", "**") }
                        Tool(Icons.Filled.FormatItalic, "Italic", enabled = items == null) { wrapSelection("*", "*") }
                        Tool(Icons.AutoMirrored.Filled.Undo, "Undo", enabled = histIdx > 0) { undo() }
                        Tool(Icons.AutoMirrored.Filled.Redo, "Redo", enabled = histIdx < history.size - 1) { redo() }
                    }
                    Row(
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp, bottom = 10.dp),
                    ) {
                        TextButton(onClick = onDismiss) { Text("Cancel") }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                            onConfirm(
                                t, bodyForSave(), c, selIds.toList(), reminder, repeatSel,
                                checklistForSave(), staged.toList(),
                            )
                        }) { Text("Save") }
                    }
                }
            }
        }
    }
    if (drawOpen) {
        DrawingEditorDialog(
            onDismiss = { drawOpen = false },
            onSave = { strokes ->
                insertDrawing(java.util.UUID.randomUUID().toString(), strokes)
                drawOpen = false
            },
        )
    }
    viewing?.let { current ->
        Dialog(onDismissRequest = { viewing = null }) {
            Card(shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp)) {
                    val full = remember(current.dataUrl) {
                        attachmentBitmap(current.dataUrl)?.asImageBitmap()
                    }
                    if (full != null) {
                        Image(
                            bitmap = full,
                            contentDescription = current.name,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(current.name, fontSize = 12.sp, color = Color.Gray)
                    Spacer(Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.End,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        TextButton(onClick = { viewing = null }) { Text("Close") }
                    }
                }
            }
        }
    }
    if (showDate) {
        val dateState = rememberDatePickerState(
            initialSelectedDateMillis = reminder ?: System.currentTimeMillis(),
        )
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    pendingDate = dateState.selectedDateMillis ?: System.currentTimeMillis()
                    showDate = false
                    showTime = true
                }) { Text("Next") }
            },
            dismissButton = {
                TextButton(onClick = { showDate = false }) { Text("Cancel") }
            },
        ) {
            DatePicker(state = dateState)
        }
    }
    if (showTime) {
        val timeState = rememberTimePickerState()
        AlertDialog(
            onDismissRequest = { showTime = false },
            title = { Text("Reminder time") },
            text = { TimePicker(state = timeState) },
            confirmButton = {
                TextButton(onClick = {
                    val cal = java.util.Calendar.getInstance().apply {
                        timeInMillis = pendingDate
                        set(java.util.Calendar.HOUR_OF_DAY, timeState.hour)
                        set(java.util.Calendar.MINUTE, timeState.minute)
                        set(java.util.Calendar.SECOND, 0)
                        set(java.util.Calendar.MILLISECOND, 0)
                    }
                    reminder = cal.timeInMillis
                    ensureReminderPermission()
                    showTime = false
                }) { Text("Set") }
            },
            dismissButton = {
                TextButton(onClick = { showTime = false }) { Text("Cancel") }
            },
        )
    }
}
