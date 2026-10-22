package dev.cstraka.keeps.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import dev.cstraka.keeps.update.UpdateUi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.TipsAndUpdates
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.cstraka.keeps.sync.Attachment
import dev.cstraka.keeps.sync.ChecklistItem
import dev.cstraka.keeps.sync.DrawingStroke
import dev.cstraka.keeps.sync.Label
import dev.cstraka.keeps.sync.Note
import dev.cstraka.keeps.sync.SpanKind
import dev.cstraka.keeps.sync.SyncStatus
import dev.cstraka.keeps.sync.parseRichBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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

internal val COLOR_KEYS = listOf(
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
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
    /** Ideas label id while the composer is an idea (typed fallback). */
    ideaLabelId: String? = null,
    /** Idea button: voice first, typed composer as the fallback. */
    onIdea: () -> Unit = {},
    onOpenEditor: (Note?) -> Unit,
    onCloseEditor: () -> Unit,
    onSave: (Note, NoteEdit) -> Unit,
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
    /** Self-updater state for the "Check for updates" row. */
    update: UpdateUi = UpdateUi(),
    onCheckUpdate: () -> Unit = {},
    /** "Install unknown apps" granted to Keeps (shown in Settings). */
    canInstallUpdates: Boolean = true,
    onAllowInstalls: () -> Unit = {},
    onExport: () -> Unit = {},
    onImportJson: (String) -> Unit = {},
    onRenameLabel: (String, String) -> Unit = { _, _ -> },
    onDeleteLabel: (String) -> Unit = {},
) {
    val drawer = androidx.compose.material3.rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val haptics = LocalHapticFeedback.current
    val context = LocalContext.current
    val dark = themeDark(theme)
    var settingsOpen by remember { mutableStateOf(false) }
    /** The open note was tapped on its title (caret there), not its body. */
    var editAtTitle by remember { mutableStateOf(false) }
    // Label being renamed/deleted via drawer long-press; null hides.
    var labelDialog by remember { mutableStateOf<Label?>(null) }
    // The search field used to keep focus (and the keyboard) through taps
    // on the grid, tab and drawer switches, and the editor opening. Every
    // one of those now clears it; Back does too while it is focused.
    val focusManager = LocalFocusManager.current
    var searchFocused by remember { mutableStateOf(false) }
    BackHandler(enabled = searchFocused) { focusManager.clearFocus() }
    LaunchedEffect(state.editing, composerOpen, settingsOpen, drawer.isOpen) {
        if (state.editing != null || composerOpen || settingsOpen || drawer.isOpen) {
            focusManager.clearFocus()
        }
    }
    // Backup file picker for Settings → Import (same JSON Export writes).
    val importPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                val text = try {
                    context.contentResolver.openInputStream(uri)
                        ?.bufferedReader()?.use { it.readText() }
                } catch (_: Exception) {
                    null
                }
                if (text != null) onImportJson(text)
            }
        }
    }
    var listView by rememberSaveable { mutableStateOf(false) }
    // Note being long-press dragged; while non-null the filing bar is shown.
    var draggingNote by remember { mutableStateOf<Note?>(null) }

    fun handleNoteDrop(target: NoteDropTarget, note: Note) {
        when (target) {
            NoteDropTarget.Archive ->
                haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
            NoteDropTarget.Trash ->
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            is NoteDropTarget.LabelDrop ->
                haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
        }
        applyNoteDrop(
            target = target,
            note = note,
            labels = state.labels,
            onArchive = onArchive,
            onTrash = onTrash,
            onAddLabel = { n, lab ->
                onSaveExtras(n, n.labelIds + lab.id, n.reminderAt, n.repeat)
            },
        )
        draggingNote = null
    }

    LaunchedEffect(deleteError) {
        if (deleteError) {
            haptics.performHapticFeedback(HapticFeedbackType.Reject)
            snackbar.showSnackbar("Go online first — Trash stays until it syncs.")
            onClearDeleteError()
        }
    }

    MaterialTheme(colorScheme = keepsColorScheme(dark)) {
        Surface(modifier = Modifier.fillMaxSize()) {
        ModalNavigationDrawer(
            drawerState = drawer,
            // Settings covers the notes; keep them out of TalkBack and tests.
            modifier = if (settingsOpen) Modifier.clearAndSetSemantics {} else Modifier,
            drawerContent = {
                ModalDrawerSheet {
                    Text("Keeps", style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(16.dp))
                    for ((label, f, icon) in listOf(
                        Triple("Notes", NoteFilter.NOTES, Icons.Filled.Lightbulb),
                        Triple("Ideas", NoteFilter.IDEAS, Icons.Filled.TipsAndUpdates),
                        Triple("Reminders", NoteFilter.REMINDERS, Icons.Filled.Notifications),
                        Triple("Archive", NoteFilter.ARCHIVE, Icons.Filled.Archive),
                        Triple("Trash", NoteFilter.TRASH, Icons.Filled.Delete),
                    )) {
                        NavigationDrawerItem(
                            label = { Text(label) },
                            icon = { Icon(icon, contentDescription = null) },
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
                            // Not NavigationDrawerItem: its inner clickable
                            // Surface takes the press, so an outer
                            // combinedClickable never saw the long press.
                            LabelDrawerItem(
                                name = lab.name,
                                selected = state.labelFilter == lab.id,
                                onClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                                    onFilter(NoteFilter.NOTES)
                                    onLabelFilter(if (state.labelFilter == lab.id) null else lab.id)
                                    scope.launch { drawer.close() }
                                },
                                onLongClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    labelDialog = lab
                                },
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    NavigationDrawerItem(
                        label = { Text("Settings") },
                        icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
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
                            IconButton(onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                                onIdea()
                            }) {
                                Icon(Icons.Filled.TipsAndUpdates, contentDescription = "New idea")
                            }
                            Text(
                                syncLabel(state.syncStatus),
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(end = 4.dp),
                            )
                            IconButton(onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                                listView = !listView
                            }) {
                                Icon(
                                    if (listView) Icons.Filled.ViewModule else Icons.Filled.ViewList,
                                    contentDescription = if (listView) "Grid view" else "List view",
                                )
                            }
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
                        visible = state.filter == NoteFilter.NOTES ||
                            state.filter == NoteFilter.IDEAS ||
                            state.filter == NoteFilter.REMINDERS,
                        enter = fadeIn(tween(180)) + scaleIn(tween(180)),
                        exit = fadeOut(tween(150)) + scaleOut(tween(150)),
                    ) {
                        // On the Ideas tab + captures an idea (voice first).
                        val ideas = state.filter == NoteFilter.IDEAS
                        FloatingActionButton(onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                            if (ideas) onIdea() else onComposerOpen(true)
                        }) {
                            Icon(
                                if (ideas) Icons.Filled.TipsAndUpdates else Icons.Filled.Add,
                                contentDescription = if (ideas) "New idea" else "Take a note",
                            )
                        }
                    }
                },
            ) { padding ->
              Column(Modifier.fillMaxSize().padding(padding)) {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = onQuery,
                    placeholder = {
                        Text(if (state.filter == NoteFilter.IDEAS) "Search ideas" else "Search notes")
                    },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    trailingIcon = if (state.query.isNotEmpty()) {
                        {
                            IconButton(onClick = {
                                onQuery("")
                                focusManager.clearFocus()
                            }) {
                                Icon(Icons.Filled.Close, contentDescription = "Clear search")
                            }
                        }
                    } else {
                        null
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                        .onFocusChanged { searchFocused = it.isFocused }
                        .testTag("searchField"),
                )
                // Notes | Ideas: ideas get their own tab instead of mixing
                // into the notes grid. Hidden on the other drawer views.
                if (state.labelFilter == null &&
                    (state.filter == NoteFilter.NOTES || state.filter == NoteFilter.IDEAS)
                ) {
                    val tabs = listOf(NoteFilter.NOTES to "Notes", NoteFilter.IDEAS to "Ideas")
                    PrimaryTabRow(
                        selectedTabIndex = tabs.indexOfFirst { it.first == state.filter },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        for ((f, name) in tabs) {
                            Tab(
                                selected = state.filter == f,
                                onClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                                    focusManager.clearFocus()
                                    onFilter(f)
                                },
                                text = { Text(name) },
                                modifier = Modifier.testTag("Tab-$name"),
                            )
                        }
                    }
                }
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
                        // Any touch on the grid (tap, scroll start) drops
                        // search focus; the Initial pass leaves the gesture
                        // itself for the cards and the scroller.
                        modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    if (event.changes.any { it.changedToDown() }) {
                                        focusManager.clearFocus()
                                    }
                                }
                            }
                        },
                    ) { _ ->
                        if (state.notes.isEmpty()) {
                            Box(Modifier.fillMaxSize()) {
                                Text(
                                    when (state.filter) {
                                        NoteFilter.NOTES -> "No notes yet — tap + to take one."
                                        NoteFilter.IDEAS -> "No ideas yet — tap the bulb to say one."
                                        NoteFilter.REMINDERS -> "No upcoming reminders."
                                        NoteFilter.ARCHIVE -> "Nothing archived."
                                        NoteFilter.TRASH -> "Trash is empty."
                                    },
                                    modifier = Modifier.padding(24.dp),
                                )
                            }
                        } else {
                            @Composable
                            fun card(note: Note, modifier: Modifier = Modifier) {
                                NoteCard(
                                    note = note,
                                    drawings = state.drawings,
                                    labels = state.labels,
                                    filter = state.filter,
                                    dark = dark,
                                    modifier = modifier,
                                    onOpen = {
                                        editAtTitle = false
                                        onOpenEditor(note)
                                    },
                                    onOpenTitle = {
                                        editAtTitle = true
                                        onOpenEditor(note)
                                    },
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
                                    onDragStart = {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        draggingNote = it
                                    },
                                )
                            }
                            Box(Modifier.fillMaxSize()) {
                                if (listView) {
                                    LazyColumn(
                                        modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        items(state.notes, key = { it.id }) { note ->
                                            card(note, Modifier.animateItem())
                                        }
                                    }
                                } else {
                                    LazyVerticalStaggeredGrid(
                                        columns = StaggeredGridCells.Fixed(2),
                                        modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalItemSpacing = 8.dp,
                                    ) {
                                        items(state.notes, key = { it.id }) { note ->
                                            card(note, Modifier.animateItem())
                                        }
                                    }
                                }
                                // Filing bar overlays the list while a card is dragged.
                                val filing = draggingNote
                                if (filing != null) {
                                    NoteDropTargets(
                                        labels = state.labels,
                                        onDrop = { target -> handleNoteDrop(target, filing) },
                                        onCancel = { draggingNote = null },
                                        modifier = Modifier.align(Alignment.BottomCenter)
                                            .padding(bottom = 88.dp),
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
                title = if (ideaLabelId != null) "New idea" else "Take a note…",
                initialTitle = "",
                initialBody = "",
                initialColor = "default",
                dark = dark,
                initialLabelIds = listOfNotNull(ideaLabelId),
                allLabels = state.labels,
                onCreateLabel = onCreateLabel,
                startWithReminderPicker = state.filter == NoteFilter.REMINDERS,
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
                startInBody = !editAtTitle,
                allLabels = state.labels,
                onCreateLabel = onCreateLabel,
                onDismiss = onCloseEditor,
                // One callback, one write: a second launched writer here
                // used to read the row before the first wrote it and put
                // the old title/body back (lost edits, 2026-10-13).
                onConfirm = { t, b, c, labIds, reminder, repeat, checklist, attachments ->
                    onSave(note, NoteEdit(t, b, c, labIds, reminder, repeat, checklist, attachments))
                },
                onSaveDrawing = onSaveDrawing,
            )
        }

        if (settingsOpen) {
            SettingsScreen(
                deviceName = deviceName,
                message = settingsMessage,
                busy = settingsBusy,
                onRename = onRename,
                onRotate = onRotate,
                theme = theme,
                onTheme = onTheme,
                appVersion = appVersion,
                update = update,
                canInstallUpdates = canInstallUpdates,
                onCheckUpdate = onCheckUpdate,
                onAllowInstalls = onAllowInstalls,
                onExport = onExport,
                onImport = { importPicker.launch(arrayOf("application/json")) },
                onSignOut = {
                    settingsOpen = false
                    onClearSettingsMessage()
                    onSignOut()
                },
                onBack = {
                    settingsOpen = false
                    onClearSettingsMessage()
                },
            )
        }

        // Drawer long-press target: rename in place or delete (tombstone).
        labelDialog?.let { lab ->
            var draft by remember(lab.id) { mutableStateOf(lab.name) }
            AlertDialog(
                onDismissRequest = { labelDialog = null },
                title = { Text("Edit label") },
                text = {
                    Column {
                        OutlinedTextField(
                            value = draft,
                            onValueChange = { draft = it },
                            label = { Text("Label name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("labelNameField"),
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                            onRenameLabel(lab.id, draft)
                            labelDialog = null
                        },
                        enabled = draft.isNotBlank(),
                    ) { Text("Rename") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onDeleteLabel(lab.id)
                        labelDialog = null
                    }) { Text("Delete") }
                },
            )
        }
        }
    }
}

/** A drawer row that looks like NavigationDrawerItem but takes a long press. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LabelDrawerItem(
    name: String,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val content = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant
    Row(
        modifier = Modifier
            .padding(horizontal = 8.dp)
            .fillMaxWidth()
            .height(56.dp)
            .clip(CircleShape)
            .background(if (selected) colors.secondaryContainer else Color.Transparent)
            .combinedClickable(role = Role.Tab, onClick = onClick, onLongClick = onLongClick)
            .semantics { this.selected = selected }
            .padding(start = 16.dp, end = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Label, contentDescription = null, tint = content)
        Spacer(Modifier.width(12.dp))
        Text(name, color = content)
    }
}
