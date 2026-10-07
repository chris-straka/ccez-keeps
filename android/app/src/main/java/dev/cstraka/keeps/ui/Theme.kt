package dev.cstraka.keeps.ui

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** In-app theme override. SYSTEM follows the OS; explicit choices persist. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

class ThemeStore(context: Context) {
    private val prefs = context.getSharedPreferences("ui", Context.MODE_PRIVATE)

    fun get(): ThemeMode = try {
        ThemeMode.valueOf(prefs.getString("theme", ThemeMode.SYSTEM.name)!!)
    } catch (e: Exception) {
        ThemeMode.SYSTEM
    }

    fun set(mode: ThemeMode) {
        prefs.edit().putString("theme", mode.name).apply()
    }
}

/** Resolve the override to the scheme actually rendered. */
@Composable
fun themeDark(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
}

/**
 * Keep's neutral greys with a blue accent (same tokens as web/styles.css),
 * instead of Material's stock purple baseline.
 */
fun keepsColorScheme(dark: Boolean): ColorScheme = if (dark) {
    darkColorScheme(
        primary = Color(0xFF8AB4F8),
        onPrimary = Color(0xFF202124),
        primaryContainer = Color(0xFF41331C),
        onPrimaryContainer = Color(0xFFFEEFC3),
        secondaryContainer = Color(0xFF41331C),
        onSecondaryContainer = Color(0xFFFEEFC3),
        background = Color(0xFF1B1C1E),
        onBackground = Color(0xFFE8EAED),
        surface = Color(0xFF1B1C1E),
        onSurface = Color(0xFFE8EAED),
        surfaceVariant = Color(0xFF242526),
        onSurfaceVariant = Color(0xFF9AA0A6),
        surfaceContainerLowest = Color(0xFF1B1C1E),
        surfaceContainerLow = Color(0xFF202124),
        surfaceContainer = Color(0xFF242526),
        surfaceContainerHigh = Color(0xFF2D2E30),
        surfaceContainerHighest = Color(0xFF35363A),
        outline = Color(0xFF4A4D52),
        outlineVariant = Color(0xFF3C4043),
    )
} else {
    lightColorScheme(
        primary = Color(0xFF1A73E8),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFFEEFC3),
        onPrimaryContainer = Color(0xFF202124),
        secondaryContainer = Color(0xFFFEEFC3),
        onSecondaryContainer = Color(0xFF202124),
        background = Color.White,
        onBackground = Color(0xFF202124),
        surface = Color.White,
        onSurface = Color(0xFF202124),
        surfaceVariant = Color(0xFFF1F3F4),
        onSurfaceVariant = Color(0xFF5F6368),
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = Color(0xFFF8F9FA),
        surfaceContainer = Color(0xFFF1F3F4),
        surfaceContainerHigh = Color(0xFFECEEF0),
        surfaceContainerHighest = Color(0xFFE8EAED),
        outline = Color(0xFFDADCE0),
        outlineVariant = Color(0xFFE8EAED),
    )
}
