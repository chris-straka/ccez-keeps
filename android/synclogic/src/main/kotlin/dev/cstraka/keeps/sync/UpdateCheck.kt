package dev.cstraka.keeps.sync

import kotlinx.serialization.Serializable

/**
 * In-app update nudge. The app compares its own BuildConfig.VERSION_NAME
 * against the newest GitHub release tag and offers the release page only
 * when behind. Network failures mean "unknown", never "update available".
 */
@Serializable
private data class LatestRelease(val tag_name: String = "")

/** Extract `tag_name` from a `releases/latest` response; null when absent. */
fun parseLatestTag(body: String): String? {
    return try {
        apiJson.decodeFromString<LatestRelease>(body).tag_name.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }
}

private val SEMVER_PREFIX = Regex("^v?(\\d+(?:\\.\\d+)*)")

/** Numeric dot-separated compare; unparseable sides are never "newer". */
fun isNewer(latest: String, current: String): Boolean {
    val l = SEMVER_PREFIX.find(latest.trim())?.groupValues?.get(1) ?: return false
    val c = SEMVER_PREFIX.find(current.trim())?.groupValues?.get(1) ?: return false
    val lp = l.split(".").map { it.toIntOrNull() ?: 0 }
    val cp = c.split(".").map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(lp.size, cp.size)) {
        val d = (lp.getOrElse(i) { 0 }) - (cp.getOrElse(i) { 0 })
        if (d != 0) return d > 0
    }
    return false
}
