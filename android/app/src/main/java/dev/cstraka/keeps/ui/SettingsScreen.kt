package dev.cstraka.keeps.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.SettingsBrightness
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.cstraka.keeps.update.UpdatePhase
import dev.cstraka.keeps.update.UpdateUi

/**
 * Full-screen Settings, grouped like the web app's panel: This phone,
 * Appearance, Backup, Updates, Account. Every row is a ListItem (56dp+)
 * so touch targets stay at least 48dp at any font scale.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    deviceName: String,
    message: String?,
    busy: Boolean,
    onRename: (String) -> Unit,
    onRotate: () -> Unit,
    theme: ThemeMode,
    onTheme: (ThemeMode) -> Unit,
    appVersion: String,
    update: UpdateUi,
    canInstallUpdates: Boolean,
    onCheckUpdate: () -> Unit,
    onAllowInstalls: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onSignOut: () -> Unit,
    onBack: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val largeFont = LocalDensity.current.fontScale > 1.15f
    var confirmSignOut by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)
    LaunchedEffect(message) {
        if (message != null) haptics.performHapticFeedback(HapticFeedbackType.Confirm)
    }

    Scaffold(
        modifier = Modifier.fillMaxSize().testTag("settings-screen"),
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .navigationBarsPadding(),
        ) {
            SettingsSection("This phone") {
                var nameDraft by remember(deviceName) { mutableStateOf(deviceName) }
                val canRename = !busy && nameDraft.isNotBlank() && nameDraft.trim() != deviceName
                fun rename() {
                    haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                    onRename(nameDraft)
                }
                ListItem(
                    colors = rowColors(),
                    leadingContent = { Icon(Icons.Filled.Smartphone, contentDescription = null) },
                    headlineContent = {
                        OutlinedTextField(
                            value = nameDraft,
                            onValueChange = { nameDraft = it },
                            label = { Text("This phone's name") },
                            singleLine = true,
                            enabled = !busy,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { if (canRename) rename() }),
                            trailingIcon = {
                                IconButton(onClick = { rename() }, enabled = canRename) {
                                    Icon(Icons.Filled.Check, contentDescription = "Rename")
                                }
                            },
                            modifier = Modifier.fillMaxWidth().testTag("device-name"),
                        )
                    },
                )
                if (message != null) {
                    Text(
                        message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(start = 56.dp, end = 16.dp, bottom = 12.dp)
                            .testTag("settings-message"),
                    )
                }
                RowDivider()
                SettingsRow(
                    icon = Icons.Filled.Key,
                    title = "New login",
                    help = "Replaces this phone's token. Revoke it anytime from the web app device list.",
                    enabled = !busy,
                ) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onRotate()
                }
            }

            SettingsSection("Appearance") {
                ListItem(
                    colors = rowColors(),
                    leadingContent = { Icon(Icons.Filled.Palette, contentDescription = null) },
                    headlineContent = { Text("Theme") },
                    supportingContent = if (largeFont) null else {
                        { ThemeSegments(theme, onTheme) }
                    },
                )
                // At large font sizes three segments get cramped: radio rows.
                if (largeFont) {
                    for (mode in ThemeMode.entries) {
                        ListItem(
                            colors = rowColors(),
                            leadingContent = {
                                RadioButton(selected = theme == mode, onClick = null)
                            },
                            headlineContent = { Text(themeLabel(mode)) },
                            modifier = Modifier
                                .selectable(selected = theme == mode, role = Role.RadioButton) {
                                    haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                                    onTheme(mode)
                                }
                                .testTag("theme-${mode.name.lowercase()}"),
                        )
                    }
                }
            }

            // Same wording as the web app's Backup row.
            SettingsSection("Backup") {
                SettingsRow(
                    icon = Icons.Filled.Upload,
                    title = "Export notes",
                    help = "Share every note as one JSON file.",
                ) {
                    haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                    onExport()
                }
                RowDivider()
                SettingsRow(
                    icon = Icons.Filled.Download,
                    title = "Import notes",
                    help = "Restore from a Keeps backup file.",
                ) {
                    haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                    onImport()
                }
            }

            SettingsSection("Updates") {
                UpdateRow(appVersion, update) {
                    haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                    onCheckUpdate()
                }
                RowDivider()
                SettingsRow(
                    icon = Icons.Filled.InstallMobile,
                    title = "Install updates",
                    help = if (canInstallUpdates) {
                        "Allowed · new versions install when you leave the app"
                    } else {
                        "Not allowed yet · tap to let Keeps install its own updates"
                    },
                    accent = !canInstallUpdates,
                    tag = "install-permission",
                ) {
                    haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                    onAllowInstalls()
                }
            }
            Text(
                "Keeps checks every 6 hours while online.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp),
            )

            SettingsSection("Account") {
                SettingsRow(
                    icon = Icons.AutoMirrored.Filled.Logout,
                    title = "Sign out this phone",
                    help = "Stops syncing until you sign in again.",
                    danger = true,
                ) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    confirmSignOut = true
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out this phone?") },
            text = {
                Text("Keeps forgets this phone's login and stops syncing. Notes already here stay; sign in again to sync them.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmSignOut = false
                        onSignOut()
                    },
                    modifier = Modifier.testTag("confirm-sign-out"),
                ) { Text("Sign out", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") }
            },
        )
    }
}

private fun themeLabel(mode: ThemeMode) = mode.name.lowercase().replaceFirstChar { it.uppercase() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeSegments(theme: ThemeMode, onTheme: (ThemeMode) -> Unit) {
    val haptics = LocalHapticFeedback.current
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        val modes = ThemeMode.entries
        modes.forEachIndexed { i, mode ->
            SegmentedButton(
                selected = theme == mode,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                    onTheme(mode)
                },
                shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                icon = {
                    SegmentedButtonDefaults.Icon(theme == mode) {
                        Icon(
                            when (mode) {
                                ThemeMode.SYSTEM -> Icons.Filled.SettingsBrightness
                                ThemeMode.LIGHT -> Icons.Filled.LightMode
                                ThemeMode.DARK -> Icons.Filled.DarkMode
                            },
                            contentDescription = null,
                        )
                    }
                },
                modifier = Modifier.heightIn(min = 48.dp).testTag("theme-${mode.name.lowercase()}"),
            ) {
                Text(themeLabel(mode), maxLines = 1)
            }
        }
    }
}

/** Header plus one rounded card holding the section's rows. */
@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .padding(start = 16.dp, top = 24.dp, bottom = 8.dp)
            .semantics { heading() },
    )
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(verticalArrangement = Arrangement.Top, content = content)
    }
}

@Composable
private fun RowDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.surface,
        thickness = 2.dp,
    )
}

@Composable
private fun rowColors() = ListItemDefaults.colors(containerColor = Color.Transparent)

@Composable
private fun SettingsRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    help: String? = null,
    enabled: Boolean = true,
    danger: Boolean = false,
    accent: Boolean = false,
    tag: String? = null,
    onClick: () -> Unit,
) {
    val tint = when {
        danger -> MaterialTheme.colorScheme.error
        accent -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    ListItem(
        colors = rowColors(),
        leadingContent = { Icon(icon, contentDescription = null, tint = tint) },
        headlineContent = {
            Text(
                title,
                color = if (danger) MaterialTheme.colorScheme.error else Color.Unspecified,
            )
        },
        supportingContent = help?.let { { Text(it) } },
        modifier = Modifier
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier),
    )
}

/**
 * Current version, last check, and what the self-updater is doing.
 * Tap = check now (and install, or open Android's prompt).
 */
@Composable
private fun UpdateRow(appVersion: String, update: UpdateUi, onClick: () -> Unit) {
    val tag = update.tag
    val status = when (update.phase) {
        UpdatePhase.CHECKING -> "Checking…"
        UpdatePhase.DOWNLOADING -> "Downloading $tag…"
        UpdatePhase.READY -> "$tag ready · installs when you leave the app"
        UpdatePhase.NEEDS_PERMISSION -> "$tag ready · tap to allow Keeps to install it"
        UpdatePhase.WAITING_FOR_YOU -> "$tag ready · tap to install"
        UpdatePhase.INSTALLING -> "Installing $tag…"
        UpdatePhase.FAILED -> "Couldn't finish · retries later"
        UpdatePhase.UP_TO_DATE -> "Up to date"
        UpdatePhase.IDLE -> if (tag != null) "$tag available" else null
    }
    val now = System.currentTimeMillis()
    val checked = if (update.lastCheck == 0L) "not checked yet"
    else if (now - update.lastCheck < 60_000L) "checked just now"
    else "checked " + android.text.format.DateUtils.getRelativeTimeSpanString(
            update.lastCheck, now, android.text.format.DateUtils.MINUTE_IN_MILLIS,
        ).toString().replaceFirstChar { it.lowercase() }
    val highlight = tag != null && update.phase != UpdatePhase.UP_TO_DATE
    ListItem(
        colors = rowColors(),
        leadingContent = {
            Icon(
                Icons.Filled.SystemUpdate,
                contentDescription = null,
                tint = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        headlineContent = {
            Text(
                "Check for updates",
                color = if (highlight) MaterialTheme.colorScheme.primary else Color.Unspecified,
            )
        },
        supportingContent = {
            Column {
                Text(
                    listOfNotNull(appVersion.takeIf { it.isNotBlank() }?.let { "Keeps $it" }, checked).joinToString(" · "),
                )
                if (status != null) {
                    Text(status, fontWeight = if (highlight) FontWeight.Bold else FontWeight.Normal)
                }
            }
        },
        modifier = Modifier
            .clickable(role = Role.Button, onClick = onClick)
            .testTag("check-updates"),
    )
}
