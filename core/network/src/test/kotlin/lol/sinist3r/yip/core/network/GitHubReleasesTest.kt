package lol.sinist3r.yip.core.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import lol.sinist3r.yip.core.network.update.GitHubReleasesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubReleasesTest {
    private fun cmp(a: String, b: String) = GitHubReleasesApi.compareVersions(a, b)

    @Test fun versions() {
        assertEquals(1, cmp("0.2.0", "0.1.0"))
        assertEquals(1, cmp("0.10.0", "0.9.9"))
        assertEquals(0, cmp("v0.2.0", "0.2.0"))
        assertEquals(1, cmp("0.2.0", "0.2.0-beta.1"))
        assertEquals(-1, cmp("0.2.0-beta.1", "0.2.0-beta.2"))
        assertEquals(0, cmp("0.2", "0.2.0"))
    }

    // Shape of GET /repos/{owner}/{repo}/releases items, as produced by the release workflow.
    private val release = Json.parseToJsonElement(
        """
        {"tag_name":"v0.2.0","name":"","draft":false,"prerelease":false,"html_url":"https://github.com/o/r/releases/tag/v0.2.0",
         "body":"## What's Changed\n* Pronouns in chat by @karasu in https://github.com/o/r/pull/4\n\n**Full Changelog**: https://github.com/o/r/compare/v0.1.0...v0.2.0",
         "assets":[
          {"name":"Yip-0.2.0.apk","size":7000000,"digest":"sha256:ABC123","browser_download_url":"https://github.com/o/r/releases/download/v0.2.0/Yip-0.2.0.apk"},
          {"name":"Yip-0.2.0.apk.sha256","size":80,"browser_download_url":"https://github.com/o/r/releases/download/v0.2.0/Yip-0.2.0.apk.sha256"}]}
        """.trimIndent(),
    ).jsonObject

    @Test fun parsesWorkflowRelease() {
        val r = GitHubReleasesApi.parse(release)!!
        assertEquals("0.2.0", r.version)
        assertEquals("v0.2.0", r.title)
        assertEquals("abc123", r.sha256)
        assertTrue(r.sha256Url!!.endsWith(".apk.sha256"))
        assertEquals("What's Changed\n• Pronouns in chat", r.notes)
    }

    @Test fun releaseWithoutApkIsIgnored() {
        val noApk = Json.parseToJsonElement("""{"tag_name":"v1.0.0","assets":[{"name":"notes.txt","browser_download_url":"https://x"}]}""").jsonObject
        assertNull(GitHubReleasesApi.parse(noApk))
    }
}
