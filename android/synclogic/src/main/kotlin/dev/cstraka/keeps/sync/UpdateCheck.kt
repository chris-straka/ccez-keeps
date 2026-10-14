package dev.cstraka.keeps.sync

import kotlinx.serialization.Serializable

/**
 * Self-update decisions, kept pure so they are unit tested here: parse the
 * `releases/latest` JSON, compare versions, pick the APK asset and its
 * published SHA-256. The app's UpdateWorker does the I/O (download, cert
 * check, PackageInstaller). Network failures mean "unknown", never
 * "update available".
 */
@Serializable
data class ReleaseAsset(
    val name: String = "",
    val size: Long = 0,
    val browser_download_url: String = "",
    /** GitHub's own checksum, `sha256:<hex>`, on assets uploaded since 2025. */
    val digest: String? = null,
)

@Serializable
data class LatestRelease(
    val tag_name: String = "",
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<ReleaseAsset> = emptyList(),
)

/** A `releases/latest` response; null when unparseable or tagless. */
fun parseLatestRelease(body: String): LatestRelease? {
    return try {
        apiJson.decodeFromString<LatestRelease>(body).takeIf { it.tag_name.isNotBlank() }
    } catch (_: Exception) {
        null
    }
}

/** Extract `tag_name` from a `releases/latest` response; null when absent. */
fun parseLatestTag(body: String): String? = parseLatestRelease(body)?.tag_name

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

/**
 * The installable APK of a release: release.yml names it
 * `Keeps-android-<tag>.apk`; any other single `.apk` is the fallback.
 * Assets without a download URL or size can't be verified, so never match.
 */
fun selectApkAsset(assets: List<ReleaseAsset>): ReleaseAsset? {
    val apks = assets.filter {
        it.name.endsWith(".apk", ignoreCase = true) &&
            it.browser_download_url.isNotBlank() && it.size > 0
    }
    return apks.firstOrNull { it.name.startsWith("Keeps-android-") } ?: apks.singleOrNull()
}

private val HEX64 = Regex("^[0-9a-fA-F]{64}$")

/** Hex SHA-256 from GitHub's `sha256:<hex>` asset digest; null otherwise. */
fun digestSha256(digest: String?): String? {
    val hex = digest?.trim()?.removePrefix("sha256:") ?: return null
    return hex.lowercase().takeIf { HEX64.matches(it) }
}

/** The `<apk>.sha256` sidecar release.yml publishes next to the APK. */
fun selectChecksumAsset(assets: List<ReleaseAsset>, apk: ReleaseAsset): ReleaseAsset? =
    assets.firstOrNull { it.name == "${apk.name}.sha256" && it.browser_download_url.isNotBlank() }

/**
 * Hash from a `sha256sum`-style file (`<hex>  <name>` per line, or a bare
 * hex). With several lines, only the one naming [fileName] counts.
 */
fun parseSha256File(text: String, fileName: String): String? {
    val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
    for (line in lines) {
        val parts = line.split(Regex("\\s+"), limit = 2)
        val hex = parts[0].lowercase()
        if (!HEX64.matches(hex)) continue
        val name = parts.getOrNull(1)?.removePrefix("*")?.trim()
        if (name == null && lines.size == 1) return hex
        if (name == fileName) return hex
    }
    return null
}

/** What the updater should fetch: a newer release's APK and its hash. */
data class UpdatePlan(
    val tag: String,
    val apk: ReleaseAsset,
    /** Hex SHA-256 from the asset digest; null means "try the sidecar". */
    val sha256: String?,
    val checksumAsset: ReleaseAsset?,
)

/**
 * Null when there is nothing to install: not newer, a draft/prerelease,
 * or no APK asset. Otherwise the asset plus whichever checksum the
 * release publishes (both null = verify by size + signing cert only).
 */
fun planUpdate(release: LatestRelease, currentVersion: String): UpdatePlan? {
    if (release.draft || release.prerelease) return null
    if (!isNewer(release.tag_name, currentVersion)) return null
    val apk = selectApkAsset(release.assets) ?: return null
    return UpdatePlan(
        tag = release.tag_name,
        apk = apk,
        sha256 = digestSha256(apk.digest),
        checksumAsset = selectChecksumAsset(release.assets, apk),
    )
}

/**
 * Android versionCode for a `major.minor.patch` name (`v` and suffixes
 * ignored); app/build.gradle.kts uses the same formula. 1 for junk, which
 * is what every release before 0.5.3 shipped with.
 */
fun versionCodeFor(versionName: String): Int {
    val parts = SEMVER_PREFIX.find(versionName.trim())?.groupValues?.get(1)
        ?.split(".")?.map { it.toIntOrNull() ?: 0 } ?: return 1
    val (major, minor, patch) = List(3) { parts.getOrElse(it) { 0 } }
    return (major * 1_000_000 + minor.coerceAtMost(999) * 1_000 + patch.coerceAtMost(999))
        .coerceAtLeast(1)
}
