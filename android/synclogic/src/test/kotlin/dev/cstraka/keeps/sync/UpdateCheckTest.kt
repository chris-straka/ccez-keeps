package dev.cstraka.keeps.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateCheckTest {

    @Test
    fun parsesTagNameAndRejectsJunk() {
        assertEquals(
            "v0.4.0",
            parseLatestTag("""{"tag_name":"v0.4.0","draft":false}"""),
        )
        assertNull(parseLatestTag("""{"tag_name":""}"""))
        assertNull(parseLatestTag("""{"nope":true}"""))
        assertNull(parseLatestTag("not json"))
    }

    @Test
    fun newerMeansNumericallyGreater() {
        assertTrue(isNewer("v0.4.0", "0.3.0"))
        assertTrue(isNewer("v0.3.1", "0.3.0"))
        assertTrue(isNewer("v0.10.0", "0.9.9"))
        assertFalse(isNewer("v0.3.0", "0.3.0"))
        assertFalse(isNewer("v0.2.0", "0.3.0"))
        assertFalse(isNewer("0.3.0", "0.3.0-dev"))
    }

    @Test
    fun unparseableSidesFailClosed() {
        assertFalse(isNewer("", "0.3.0"))
        assertFalse(isNewer("v0.4.0", ""))
        assertFalse(isNewer("latest", "0.3.0"))
        assertFalse(isNewer("v0.4.0", "dev"))
    }

    private val sha = "a".repeat(64)

    // Trimmed real shape of api.github.com/repos/.../releases/latest.
    private val github = """
        {"tag_name":"v0.6.0","draft":false,"prerelease":false,"name":"Keeps v0.6.0",
         "assets":[
          {"name":"Keeps-android-v0.6.0.apk.sha256","size":90,
           "browser_download_url":"https://example.test/Keeps-android-v0.6.0.apk.sha256"},
          {"name":"Keeps-android-v0.6.0.apk","size":12345678,"digest":"sha256:${sha.uppercase()}",
           "content_type":"application/vnd.android.package-archive",
           "browser_download_url":"https://example.test/Keeps-android-v0.6.0.apk"}
         ]}
    """.trimIndent()

    @Test
    fun parsesAssetsFromAGithubRelease() {
        val release = assertNotNull(parseLatestRelease(github))
        assertEquals(2, release.assets.size)
        val apk = assertNotNull(selectApkAsset(release.assets))
        assertEquals("Keeps-android-v0.6.0.apk", apk.name)
        assertEquals(12345678L, apk.size)
    }

    @Test
    fun plansNewerReleaseWithItsChecksums() {
        val plan = assertNotNull(planUpdate(parseLatestRelease(github)!!, "0.5.3"))
        assertEquals("v0.6.0", plan.tag)
        assertEquals(sha, plan.sha256)
        assertEquals("Keeps-android-v0.6.0.apk.sha256", plan.checksumAsset?.name)
    }

    @Test
    fun noPlanWhenCurrentOrNoApk() {
        val release = parseLatestRelease(github)!!
        assertNull(planUpdate(release, "0.6.0"))
        assertNull(planUpdate(release, "0.7.0"))
        assertNull(planUpdate(release.copy(assets = release.assets.take(1)), "0.5.0"))
        assertNull(planUpdate(release.copy(prerelease = true), "0.5.0"))
        assertNull(planUpdate(release.copy(draft = true), "0.5.0"))
    }

    @Test
    fun assetSelectionPrefersOurApkAndNeedsSizeAndUrl() {
        val ours = ReleaseAsset("Keeps-android-v1.0.0.apk", 10, "u1")
        val other = ReleaseAsset("debug.apk", 10, "u2")
        assertEquals(ours, selectApkAsset(listOf(other, ours)))
        assertEquals(other, selectApkAsset(listOf(other, ReleaseAsset("notes.txt", 5, "u3"))))
        // Two unnamed apks: ambiguous, install neither.
        assertNull(selectApkAsset(listOf(other, other.copy(name = "b.apk"))))
        assertNull(selectApkAsset(listOf(ours.copy(size = 0))))
        assertNull(selectApkAsset(listOf(ours.copy(browser_download_url = ""))))
    }

    @Test
    fun digestOnlyAcceptsSha256Hex() {
        assertEquals(sha, digestSha256("sha256:$sha"))
        assertNull(digestSha256("sha1:${"a".repeat(40)}"))
        assertNull(digestSha256("sha256:xyz"))
        assertNull(digestSha256(null))
    }

    @Test
    fun parsesSha256SumFiles() {
        assertEquals(sha, parseSha256File("$sha  Keeps-android-v0.6.0.apk\n", "Keeps-android-v0.6.0.apk"))
        assertEquals(sha, parseSha256File("$sha *Keeps-android-v0.6.0.apk", "Keeps-android-v0.6.0.apk"))
        assertEquals(sha, parseSha256File(sha, "anything.apk"))
        val b = "b".repeat(64)
        assertEquals(b, parseSha256File("$sha  other.apk\n$b  mine.apk\n", "mine.apk"))
        assertNull(parseSha256File("$sha  other.apk", "mine.apk"))
        assertNull(parseSha256File("not a hash", "mine.apk"))
    }

    @Test
    fun versionCodesGrowWithVersions() {
        assertEquals(5_003, versionCodeFor("0.5.3"))
        assertEquals(1_002_003, versionCodeFor("v1.2.3-rc1"))
        assertEquals(1, versionCodeFor("0.0.0-dev"))
        assertEquals(1, versionCodeFor("dev"))
        assertTrue(versionCodeFor("0.10.0") > versionCodeFor("0.9.9"))
        assertTrue(versionCodeFor("1.0.0") > versionCodeFor("0.999.999"))
    }
}
