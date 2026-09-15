package dev.cstraka.keeps.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TimePicker
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.cstraka.keeps.data.attachmentBitmap
import dev.cstraka.keeps.data.processImageBytes
import dev.cstraka.keeps.sync.Attachment
import dev.cstraka.keeps.sync.ChecklistItem
import dev.cstraka.keeps.sync.Drawing
import dev.cstraka.keeps.sync.DrawingStroke
import dev.cstraka.keeps.sync.Label
import dev.cstraka.keeps.sync.Note
import dev.cstraka.keeps.sync.NoteLimits
import dev.cstraka.keeps.sync.labelTint
import dev.cstraka.keeps.sync.SpanKind
import dev.cstraka.keeps.sync.SyncStatus
import dev.cstraka.keeps.sync.parseRichBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Card tints per color key. Dark is the muted set mirroring the web dark
 * theme; light is the Keep pastel set mirroring the web light theme.
 */
fun noteColor(key: String, dark: Boolean = true): Color {
    if (!dark) return when (key) {
        "red" -> Color(0xFFF28B82)
        "orange" -> Color(0xFFFbbc04)
        "yellow" -> Color(0xFFFFF475)
        "green" -> Color(0xFFCCFF90)
        "teal" -> Color(0xFFA7FFEB)
        "blue" -> Color(0xFFCBF0F8)
        "purple" -> Color(0xFFD7AEFB)
        "pink" -> Color(0xFFFDCFE8)
        else -> Color(0xFFFFFFFF)
    }
    return when (key) {
        "red" -> Color(0xFF5C2B29)
        "orange" -> Color(0xFF614A19)
        "yellow" -> Color(0xFF635D19)
        "green" -> Color(0xFF345920)
        "teal" -> Color(0xFF16504B)
        "blue" -> Color(0xFF2D555E)
        "purple" -> Color(0xFF42275B)
        "pink" -> Color(0xFF562A3E)
        else -> Color(0xFF202124)
    }
}

private val COLOR_KEYS = listOf(
    "default", "red", "orange", "yellow", "green", "teal", "blue", "purple", "pink",
)

/**
 * Display text for a note body: the same Markdown subset the web client
 * renders (contracts/data.md), mapped onto an AnnotatedString. Markers
 * never reach the string; bodies stay verbatim in Room/D1.
 */
fun richText(body: String): AnnotatedString {
    val rich = parseRichBody(body)
    return buildAnnotatedString {
        rich.lines.forEachIndexed { i, line ->
            if (i > 0) append("\n")
            val checked = line.checked
            if (checked != null) {
                withStyle(SpanStyle(color = Color.Gray)) {
                    append(if (checked) "☑ " else "☐ ")
                }
            }
            val textStart = length
            append(line.text)
            for (span in line.spans) {
                val style = when (span.kind) {
                    SpanKind.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
                    SpanKind.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
                    SpanKind.STRIKE -> SpanStyle(textDecoration = TextDecoration.LineThrough)
                    SpanKind.CODE -> SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = Color.White.copy(alpha = 0.08f),
                    )
                }
                addStyle(style, textStart + span.start, textStart + span.end)
            }
        }
    }
}

fun syncLabel(status: SyncStatus): String = when (status) {
    SyncStatus.Idle -> "Synced"
    SyncStatus.Syncing -> "Syncing…"
    SyncStatus.Offline -> "Offline"
    SyncStatus.NeedsLogin -> "Signed out"
    is SyncStatus.Error -> "Sync error"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(
    state: NotesUiState,
    needsLogin: Boolean,
    deviceName: String,
    onQuery: (String) -> Unit,
    onFilter: (NoteFilter) -> Unit,
    onLabelFilter: (String?) -> Unit = {},
    onCreate: (String, String, List<String>, Long?, String?, List<ChecklistItem>?, List<Attachment>) -> Unit,
    composerOpen: Boolean = false,
    onComposerOpen: (Boolean) -> Unit = {},
    onOpenEditor: (Note?) -> Unit,
    onCloseEditor: () -> Unit,
    onSave: (Note, String, String, String, List<ChecklistItem>?, List<Attachment>) -> Unit,
    onSaveExtras: (Note, List<String>, Long?, String?) -> Unit = { _, _, _, _ -> },
    onCreateLabel: (String, (Label) -> Unit) -> Unit = { _, _ -> },
    onSaveDrawing: (String, List<DrawingStroke>) -> Unit,
    onPin: (Note) -> Unit,
    onArchive: (Note) -> Unit,
    onTrash: (Note) -> Unit,
    onRestore: (Note) -> Unit,
    onDeleteForever: (Note) -> Unit,
    onSyncNow: () -> Unit,
    onSignOut: () -> Unit,
    deleteError: Boolean,
    onClearDeleteError: () -> Unit,
    onRename: (String) -> Unit = {},
    onRotate: () -> Unit = {},
    settingsMessage: String? = null,
    onClearSettingsMessage: () -> Unit = {},
    settingsBusy: Boolean = false,
    theme: ThemeMode = ThemeMode.DARK,
    onTheme: (ThemeMode) -> Unit = {},
    appVersion: String = "",
    onUpdate: () -> Unit = {},
    onExport: () -> Unit = {},
) {
    val drawer = androidx.compose.material3.rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val haptics = LocalHapticFeedback.current
    val dark = themeDark(theme)
    var settingsOpen by remember { mutableStateOf(false) }

    LaunchedEffect(deleteError) {
        if (deleteError) {
            haptics.performHapticFeedback(HapticFeedbackType.Reject)
            snackbar.showSnackbar("Go online first — Trash stays until it syncs.")
            onClearDeleteError()
        }
    }

    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize()) {
        ModalNavigationDrawer(
            drawerState = drawer,
            drawerContent = {
                ModalDrawerSheet {
                    Text("Keeps", style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(16.dp))
                    for ((label, f) in listOf(
                        "Notes" to NoteFilter.NOTES,
                        "Archive" to NoteFilter.ARCHIVE,
                        "Trash" to NoteFilter.TRASH,
                    )) {
                        NavigationDrawerItem(
                            label = { Text(label) },
                            selected = state.filter == f && state.labelFilter == null,
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                                onFilter(f)
                                onLabelFilter(null)
                                scope.launch { drawer.close() }
                            },
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                    }
                    if (state.labels.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Labels",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                        for (lab in state.labels) {
                            NavigationDrawerItem(
                                label = { Text(lab.name) },
                                selected = state.labelFilter == lab.id,
                                onClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                                    onFilter(NoteFilter.NOTES)
                                    onLabelFilter(if (state.labelFilter == lab.id) null else lab.id)
                                    scope.launch { drawer.close() }
                                },
                                modifier = Modifier.padding(horizontal = 8.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    NavigationDrawerItem(
                        label = { Text("Settings") },
                        selected = false,
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                            settingsOpen = true
                            scope.launch { drawer.close() }
                        },
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
            },
        ) {
            Scaffold(
                snackbarHost = { SnackbarHost(snackbar) },
                topBar = {
                    // Keep-style: slim bar on top, full-width search below
                    // it. (A search field inside the title slot slides
                    // under the actions and overlaps the sync label.)
                    TopAppBar(
                        title = { Text("Keeps") },
                        navigationIcon = {
                            IconButton(onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                                scope.launch { drawer.open() }
                            }) {
                                Icon(Icons.Filled.Menu, contentDescription = "Menu")
                            }
                        },
                        actions = {
                            Text(
                                syncLabel(state.syncStatus),
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(end = 4.dp),
                            )
                            IconButton(onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                                onSyncNow()
                            }) {
                                Icon(Icons.Filled.Refresh, contentDescription = "Sync now")
                            }
                        },
                    )
                },
                floatingActionButton = {
                    AnimatedVisibility(
                        visible = state.filter == NoteFilter.NOTES,
                        enter = fadeIn(tween(180)) + scaleIn(tween(180)),
                        exit = fadeOut(tween(150)) + scaleOut(tween(150)),
                    ) {
                        FloatingActionButton(onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                            onComposerOpen(true)
                        }) {
                            Icon(Icons.Filled.Add, contentDescription = "Take a note")
                        }
                    }
                },
            ) { padding ->
              Column(Modifier.fillMaxSize().padding(padding)) {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = onQuery,
                    placeholder = { Text("Search notes") },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                )
                Spacer(Modifier.height(4.dp))
                if (needsLogin) {
                    Box(Modifier.fillMaxSize().padding(24.dp)) {
                        Column {
                            Text("This phone's login stopped working.",
                                fontWeight = FontWeight.Bold)
                            Text("It was probably revoked. Sign out and enroll again.")
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = onSignOut) { Text("Sign out & re-enroll") }
                        }
                    }
                } else {
                    // Crossfade on filter switches; the grid itself stays
                    // static on keystrokes (enter motion there is flicker).
                    AnimatedContent(
                        targetState = state.filter,
                        label = "filter",
                        transitionSpec = {
                            (fadeIn(tween(180)) + scaleIn(tween(180), initialScale = 0.98f))
                                .togetherWith(fadeOut(tween(150)))
                        },
                        modifier = Modifier.fillMaxSize(),
                    ) { _ ->
                        if (state.notes.isEmpty()) {
                            Box(Modifier.fillMaxSize()) {
                                Text(
                                    when (state.filter) {
                                        NoteFilter.NOTES -> "No notes yet — tap + to take one."
                                        NoteFilter.ARCHIVE -> "Nothing archived."
                                        NoteFilter.TRASH -> "Trash is empty."
                                    },
                                    modifier = Modifier.padding(24.dp),
                                )
                            }
                        } else {
                            LazyVerticalStaggeredGrid(
                                columns = StaggeredGridCells.Fixed(2),
                                modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalItemSpacing = 8.dp,
                            ) {
                                items(state.notes, key = { it.id }) { note ->
                                    NoteCard(
                                        note = note,
                                        drawings = state.drawings,
                                        labels = state.labels,
                                        filter = state.filter,
                                        dark = dark,
                                        modifier = Modifier.animateItem(),
                                        onOpen = { onOpenEditor(note) },
                                        onPin = {
                                            haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                                            onPin(note)
                                        },
                                        onArchive = {
                                            haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                                            onArchive(note)
                                        },
                                        onTrash = {
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            onTrash(note)
                                        },
                                        onRestore = {
                                            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                                            onRestore(note)
                                        },
                                        onDeleteForever = {
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            onDeleteForever(note)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
              }
            }
        }

        if (composerOpen) {
            NoteDialog(
                title = "Take a note…",
                initialTitle = "",
                initialBody = "",
                initialColor = "default",
                dark = dark,
                allLabels = state.labels,
                onCreateLabel = onCreateLabel,
                onDismiss = { onComposerOpen(false) },
                onConfirm = { t, b, c, labIds, reminder, repeat, checklist, attachments ->
                    onCreate(t, b, labIds, reminder, repeat, checklist, attachments)
                    onComposerOpen(false)
                },
                onSaveDrawing = onSaveDrawing,
            )
        }

        state.editing?.let { note ->
            NoteDialog(
                title = "Edit note",
                initialTitle = note.title,
                initialBody = note.body,
                initialColor = note.color,
                dark = dark,
                initialLabelIds = note.labelIds,
                initialReminderAt = note.reminderAt,
                initialRepeat = note.repeat,
                initialChecklist = note.checklist,
                initialAttachments = note.attachments,
                allLabels = state.labels,
                onCreateLabel = onCreateLabel,
                onDismiss = onCloseEditor,
                onConfirm = { t, b, c, labIds, reminder, repeat, checklist, attachments ->
                    onSave(note, t, b, c, checklist, attachments)
                    onSaveExtras(note, labIds, reminder, repeat)
                },
                onSaveDrawing = onSaveDrawing,
            )
        }

        if (settingsOpen) {
            var nameDraft by remember(deviceName) { mutableStateOf(deviceName) }
            LaunchedEffect(settingsMessage) {
                if (settingsMessage != null) {
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                }
            }
            AlertDialog(
                onDismissRequest = {
                    settingsOpen = false
                    onClearSettingsMessage()
                },
                title = { Text("Settings") },
                text = {
                    Column {
                        OutlinedTextField(
                            value = nameDraft,
                            onValueChange = { nameDraft = it },
                            label = { Text("This phone's name") },
                            singleLine = true,
                            enabled = !settingsBusy,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                onClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                                    onRename(nameDraft)
                                },
                                enabled = !settingsBusy,
                            ) { Text("Rename") }
                            TextButton(
                                onClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onRotate()
                                },
                                enabled = !settingsBusy,
                            ) { Text("New login") }
                        }
                        if (settingsMessage != null) {
                            Spacer(Modifier.height(4.dp))
                            Text(settingsMessage, style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("Theme", style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (mode in ThemeMode.entries) {
                                val selected = theme == mode
                                TextButton(onClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                                    onTheme(mode)
                                }) {
                                    Text(
                                        mode.name.lowercase().replaceFirstChar { it.uppercase() },
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("Revoke it anytime from the web app device list.",
                            style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(4.dp))
                        if (appVersion.isNotBlank()) {
                            Text("Keeps $appVersion",
                                style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(4.dp))
                        }
                        TextButton(onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                            onUpdate()
                        }) { Text("Get the latest update") }
                        TextButton(onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                            onExport()
                        }) { Text("Export notes") }
                        TextButton(onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            settingsOpen = false
                            onClearSettingsMessage()
                            onSignOut()
                        }) { Text("Sign out this phone") }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        settingsOpen = false
                        onClearSettingsMessage()
                    }) { Text("Close") }
                },
            )
        }
        }
    }
}

private val DRAWING_REF_RE = Regex("""^!\[drawing\]\(([^)\s]+)\)$""")

private sealed interface BodyBlock {
    data class Text(val text: AnnotatedString) : BodyBlock
    data class Drawing(val id: String) : BodyBlock
}

/**
 * Note body with drawing markers resolved to thumbnails. Marker lines
 * become canvases (web parity: same strokes, same 3:2 scale); text lines
 * render through the shared Markdown subset. Unknown ids mean the drawing
 * has not synced down yet.
 */
@Composable
private fun NoteBody(body: String, drawings: Map<String, Drawing>) {
    val blocks = remember(body, drawings) {
        val out = mutableListOf<BodyBlock>()
        val chunk = StringBuilder()
        fun flushText() {
            if (chunk.isNotEmpty()) {
                out.add(BodyBlock.Text(richText(chunk.toString().removeSuffix("\n"))))
                chunk.clear()
            }
        }
        for (line in body.split("\n")) {
            val ref = DRAWING_REF_RE.matchEntire(line.trim())?.groupValues?.get(1)
            if (ref != null) {
                flushText()
                out.add(BodyBlock.Drawing(ref))
            } else {
                chunk.append(line).append("\n")
            }
        }
        flushText()
        out.toList()
    }
    Column {
        for (block in blocks) {
            when (block) {
                is BodyBlock.Text -> Text(block.text)
                is BodyBlock.Drawing -> {
                    val strokes = drawings[block.id]?.takeUnless { it.deleted }?.strokes
                    if (strokes != null) {
                        DrawingThumbnail(strokes)
                        Spacer(Modifier.height(4.dp))
                    } else {
                        Text(
                            "[drawing — not synced yet]",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray,
                            fontStyle = FontStyle.Italic,
                        )
                    }
                }
            }
        }
    }
}

/** Short display form for a reminder fire time ("Jun 3, 4:30 PM"). */
fun formatReminder(at: Long): String =
    SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(at))

private const val CHECKLIST_PREVIEW_LIMIT = 5
private const val ATTACHMENT_PREVIEW_LIMIT = 3

/** Read-only item rows for the card; hidden when the note has no checklist. */
@Composable
private fun ChecklistPreview(checklist: List<ChecklistItem>?) {
    if (checklist == null) return
    Column {
        for (item in checklist.take(CHECKLIST_PREVIEW_LIMIT)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (item.checked) "☑" else "☐",
                    color = Color.Gray,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    item.text.ifBlank { " " },
                    fontSize = 13.sp,
                    textDecoration = if (item.checked) TextDecoration.LineThrough else null,
                    color = if (item.checked) Color.Gray else Color.Unspecified,
                )
            }
        }
        val extra = checklist.size - CHECKLIST_PREVIEW_LIMIT
        if (extra > 0) Text("+$extra more", fontSize = 11.sp, color = Color.Gray)
    }
}

/** Thumbnail strip for the card; hidden when there are no attachments. */
@Composable
private fun AttachmentStrip(attachments: List<Attachment>) {
    if (attachments.isEmpty()) return
    val shown = attachments.take(ATTACHMENT_PREVIEW_LIMIT)
    Column {
        for (a in shown) {
            val bitmap = remember(a.thumbUrl) { attachmentBitmap(a.thumbUrl)?.asImageBitmap() }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = a.name,
                    contentScale = ContentScale.Crop,
                    modifier = if (shown.size == 1) {
                        Modifier.fillMaxWidth().heightIn(max = 180.dp)
                            .clip(RoundedCornerShape(6.dp))
                    } else {
                        Modifier.size(64.dp).clip(RoundedCornerShape(6.dp))
                    },
                )
                Spacer(Modifier.height(4.dp))
            }
        }
        val extra = attachments.size - ATTACHMENT_PREVIEW_LIMIT
        if (extra > 0) Text("+$extra more", fontSize = 11.sp, color = Color.Gray)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NoteCard(
    note: Note,
    drawings: Map<String, Drawing>,
    labels: List<Label> = emptyList(),
    filter: NoteFilter,
    dark: Boolean = true,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
    onPin: () -> Unit,
    onArchive: () -> Unit,
    onTrash: () -> Unit,
    onRestore: () -> Unit,
    onDeleteForever: () -> Unit,
) {
    val attached = remember(note.labelIds, labels) {
        note.labelIds.mapNotNull { id -> labels.firstOrNull { it.id == id } }
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = noteColor(note.color, dark)),
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(
            onClick = onOpen,
        ),
    ) {
        Column(Modifier.padding(12.dp).animateContentSize()) {
            if (note.title.isNotBlank()) {
                Text(note.title, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
            }
            AttachmentStrip(note.attachments)
            if (note.checklist != null) {
                ChecklistPreview(note.checklist)
            } else if (note.body.isNotBlank()) {
                NoteBody(note.body, drawings)
            }
            if (attached.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    for (lab in attached) {
                        val tint = Color(labelTint(lab.color, dark))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .border(1.dp, tint, RoundedCornerShape(10.dp))
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            Text(lab.name, fontSize = 11.sp, color = tint)
                        }
                    }
                }
            }
            note.reminderAt?.let { at ->
                val overdue = at < System.currentTimeMillis()
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Notifications, contentDescription = null,
                        tint = if (overdue) Color(0xFFEF9A9A) else Color.Gray,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        formatReminder(at) +
                            if (overdue) " • Overdue" else "" +
                            when (note.repeat) {
                                "daily" -> " • Daily"
                                "weekly" -> " • Weekly"
                                else -> ""
                            },
                        fontSize = 11.sp,
                        color = if (overdue) Color(0xFFEF9A9A) else Color.Gray,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row {
                if (filter != NoteFilter.TRASH) {
                    IconButton(onClick = onPin, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Filled.PushPin, contentDescription = if (note.pinned) "Unpin" else "Pin",
                            tint = if (note.pinned) MaterialTheme.colorScheme.onSurface else Color.Gray)
                    }
                    IconButton(onClick = onArchive, modifier = Modifier.size(36.dp)) {
                        Icon(
                            if (note.archived) Icons.Filled.Unarchive else Icons.Filled.Archive,
                            contentDescription = "Archive",
                        )
                    }
                    IconButton(onClick = onTrash, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete")
                    }
                } else {
                    TextButton(onClick = onRestore) { Text("Restore") }
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = onDeleteForever) { Text("Delete forever") }
                }
            }
        }
    }
}

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
    allLabels: List<Label> = emptyList(),
    onCreateLabel: (String, (Label) -> Unit) -> Unit = { _, _ -> },
    onDismiss: () -> Unit,
    onConfirm: (String, String, String, List<String>, Long?, String?, List<ChecklistItem>?, List<Attachment>) -> Unit,
    onSaveDrawing: (String, List<DrawingStroke>) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val context = LocalContext.current
    val focusRequester = remember { FocusRequester() }
    var t by remember { mutableStateOf(initialTitle) }
    var b by remember { mutableStateOf(TextFieldValue(initialBody)) }
    var c by remember { mutableStateOf(initialColor) }
    val selIds = remember { mutableStateListOf(*initialLabelIds.toTypedArray()) }
    var reminder by remember { mutableStateOf(initialReminderAt) }
    var repeatSel by remember { mutableStateOf(initialRepeat) }
    var labelPickerOpen by remember { mutableStateOf(false) }
    var newLabelName by remember { mutableStateOf("") }
    var pendingSelect by remember { mutableStateOf<String?>(null) }
    var showDate by remember { mutableStateOf(false) }
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
    // Best effort: headless harnesses may have nothing to take focus.
    LaunchedEffect(Unit) {
        try {
            focusRequester.requestFocus()
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
    // Focus-mode editor: full-screen dim, centered card, title focused.
    // (AlertDialog's stock scrim and sizing bury the editor on big phones.)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier.fillMaxSize()
                .background(Color.Black.copy(alpha = 0.7f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                )
                .padding(horizontal = 24.dp, vertical = 32.dp),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedVisibility(
                visible = true,
                enter = fadeIn(tween(150)) + scaleIn(tween(150), initialScale = 0.96f),
            ) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(title, style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.height(12.dp))
                OutlinedTextField(value = t, onValueChange = { t = it },
                    label = { Text("Title") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester))
                Spacer(Modifier.height(8.dp))
                val rows = items
                if (rows == null) {
                    OutlinedTextField(value = b, onValueChange = { b = it; pushHist(it.text) },
                        label = { Text("Body") }, minLines = 3,
                        modifier = Modifier.fillMaxWidth())
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
                                OutlinedTextField(
                                    value = item.text,
                                    onValueChange = { text ->
                                        items = rows.map {
                                            if (it.id == item.id) it.copy(text = text) else it
                                        }
                                    },
                                    placeholder = { Text("List item") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(onClick = {
                                    items = rows.filter { it.id != item.id }
                                }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Delete item")
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
                Spacer(Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    // 12sp: full labels fit the row instead of truncating.
                    TextButton(
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                            undo()
                        }, enabled = histIdx > 0,
                        modifier = Modifier.weight(1f),
                    ) { Text("Undo", maxLines = 1, fontSize = 12.sp) }
                    TextButton(
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                            redo()
                        }, enabled = histIdx < history.size - 1,
                        modifier = Modifier.weight(1f),
                    ) { Text("Redo", maxLines = 1, fontSize = 12.sp) }
                    TextButton(
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                            wrapSelection("**", "**")
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("B", fontWeight = FontWeight.Bold, maxLines = 1, fontSize = 12.sp) }
                    TextButton(
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                            wrapSelection("*", "*")
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("I", fontStyle = FontStyle.Italic, maxLines = 1, fontSize = 12.sp) }
                    TextButton(
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                            toggleList()
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("List", maxLines = 1, fontSize = 12.sp) }
                    TextButton(
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                            toChecklistMode()
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Checks", maxLines = 1, fontSize = 12.sp) }
                    TextButton(
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                            if (staged.size < NoteLimits.MAX_ATTACHMENTS) {
                                photoPicker.launch(
                                    PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly,
                                    ),
                                )
                            } else {
                                attachError = "At most ${NoteLimits.MAX_ATTACHMENTS} photos per note."
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Photo", maxLines = 1, fontSize = 12.sp) }
                    TextButton(
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                            drawOpen = true
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Draw", maxLines = 1, fontSize = 12.sp) }
                }
                if (staged.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
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
                                        modifier = Modifier.size(72.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .clickable { viewing = a },
                                    )
                                } else {
                                    Text(a.name, fontSize = 11.sp)
                                }
                                TextButton(
                                    onClick = { staged.remove(a) },
                                    modifier = Modifier.size(28.dp),
                                ) { Text("×", fontSize = 12.sp) }
                            }
                        }
                    }
                }
                attachError?.let { err ->
                    Spacer(Modifier.height(4.dp))
                    Text(err, fontSize = 12.sp, color = Color(0xFFE5534B))
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (key in COLOR_KEYS) {
                        val selected = c == key
                        Box(
                            Modifier.size(28.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(noteColor(key, dark))
                                .clickable {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    c = key
                                }
                                .padding(if (selected) 6.dp else 0.dp),
                        ) {
                            if (selected) {
                                Box(
                                    Modifier.fillMaxSize()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color.White.copy(alpha = 0.85f)),
                                )
                            }
                        }
                    }
                }
                        Spacer(Modifier.height(8.dp))
                        // Labels: selected chips plus a picker with create.
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Labels", style = MaterialTheme.typography.labelLarge)
                            Spacer(Modifier.width(8.dp))
                            TextButton(onClick = { labelPickerOpen = !labelPickerOpen }) {
                                Text(if (labelPickerOpen) "Done" else "Edit")
                            }
                        }
                        if (selIds.isNotEmpty()) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                val byId = allLabels.associateBy { it.id }
                                for (id in selIds.toList()) {
                                    val name = byId[id]?.name ?: "label"
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(
                                                MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                                            )
                                            .clickable { selIds.remove(id) }
                                            .padding(horizontal = 8.dp, vertical = 2.dp),
                                    ) {
                                        Text("$name ✕", fontSize = 12.sp)
                                    }
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                        }
                        if (labelPickerOpen) {
                            Column {
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
                            Spacer(Modifier.height(4.dp))
                        }
                        // Reminder: date then time pickers; no exact alarms.
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Notifications, contentDescription = null,
                                modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                reminder?.let { formatReminder(it) } ?: "No reminder",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            if (reminder != null) {
                                TextButton(onClick = {
                                    reminder = null
                                    repeatSel = null
                                }) { Text("Clear") }
                            }
                            TextButton(onClick = { showDate = true }) {
                                Text(if (reminder == null) "Add" else "Change")
                            }
                        }
                        // Repeat only exists attached to a reminder.
                        if (reminder != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "Repeats",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                for ((label, value) in listOf("None" to null, "Daily" to "daily", "Weekly" to "weekly")) {
                                    val selected = repeatSel == value
                                    TextButton(onClick = {
                                        haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                                        repeatSel = value
                                    }) {
                                        Text(
                                            label,
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(
                            horizontalArrangement = Arrangement.End,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            TextButton(onClick = onDismiss) { Text("Cancel") }
                            Spacer(Modifier.width(8.dp))
                            TextButton(onClick = {
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
