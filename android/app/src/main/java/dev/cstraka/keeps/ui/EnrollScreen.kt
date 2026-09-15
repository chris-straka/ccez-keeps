package dev.cstraka.keeps.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp

/**
 * First-launch gate. One button opens the Access login in the system
 * browser (Custom Tab); the server redirects back into the app with a
 * single-use code, and exchange mints the permanent device token.
 */
@Composable
fun EnrollScreen(
    state: EnrollState,
    deviceName: String,
    onDeviceName: (String) -> Unit,
    onLogin: () -> Unit,
    onRetry: () -> Unit,
    theme: ThemeMode = ThemeMode.DARK,
) {
    val haptics = LocalHapticFeedback.current
    MaterialTheme(colorScheme = if (themeDark(theme)) darkColorScheme() else lightColorScheme()) {
        // Surface sets the content color (white text on dark); without it
        // plain Text() falls back to black-on-dark and is unreadable.
        Surface(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Spacer(Modifier.height(48.dp))
            Text("Keeps", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(8.dp))
            Text("Sign in once with your email. This phone gets its own " +
                "login afterward — the browser never opens again.")
            Spacer(Modifier.height(24.dp))
            OutlinedTextField(
                value = deviceName,
                onValueChange = onDeviceName,
                label = { Text("This phone's name") },
                singleLine = true,
            )
            Spacer(Modifier.height(16.dp))
            when (state) {
                EnrollState.Idle -> Button(onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                    onLogin()
                }) {
                    Text("Sign in with email")
                }
                EnrollState.WaitingInBrowser -> {
                    Text("Finish signing in, in the browser…")
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                        onLogin()
                    }) { Text("Open browser again") }
                }
                EnrollState.Exchanging -> {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(8.dp))
                    Text("Finishing enrollment…")
                }
                is EnrollState.Failed -> {
                    Text(state.message)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                        onRetry()
                    }) { Text("Try again") }
                }
            }
        }
        }
    }
}
