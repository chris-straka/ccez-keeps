package dev.cstraka.keeps.sync

/**
 * Label chip tints per color key as opaque ARGB longs: bright on dark,
 * deep on light. Callers wrap with `Color(...)`. Mirrors the web
 * `.label-chip[data-color]` rules in styles.css. Kept in synclogic (not
 * the UI layer) so the palette stays unit-tested without Compose.
 */
fun labelTint(key: String, dark: Boolean = true): Long {
    if (!dark) return when (key) {
        "red" -> 0xFFC5221F
        "orange" -> 0xFFB06000
        "yellow" -> 0xFF7A5C00
        "green" -> 0xFF1E8E3E
        "teal" -> 0xFF04786A
        "blue" -> 0xFF1A73E8
        "purple" -> 0xFF9334E6
        "pink" -> 0xFFD01884
        else -> 0xFF5F6368
    }
    return when (key) {
        "red" -> 0xFFF28B82
        "orange" -> 0xFFFbbc04
        "yellow" -> 0xFFFFF475
        "green" -> 0xFFCCFF90
        "teal" -> 0xFFA7FFEB
        "blue" -> 0xFFCBF0F8
        "purple" -> 0xFFD7AEFB
        "pink" -> 0xFFFDCFE8
        else -> 0xFF9AA0A6
    }
}
