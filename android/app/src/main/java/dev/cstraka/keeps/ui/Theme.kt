package dev.cstraka.keeps.ui

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable

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
