package lol.sinist3r.yip.core.network.update

import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import lol.sinist3r.yip.core.common.AppException
import lol.sinist3r.yip.core.network.twitch.arr
import lol.sinist3r.yip.core.network.twitch.bool
import lol.sinist3r.yip.core.network.twitch.long
import lol.sinist3r.yip.core.network.twitch.str

data class AppRelease(
    val version: String,
    val title: String,
    val notes: String,
    val pageUrl: String,
    val prerelease: Boolean,
    val apkUrl: String,
    val apkSize: Long,
    /** Lowercase hex, from GitHub's asset digest or the release's .sha256 file. */
    val sha256: String?,
    val sha256Url: String?,
)

@Singleton
class GitHubReleasesApi @Inject constructor(private val http: HttpClient, private val json: Json) {

    /** Newest published release with an APK, or null if there are none. */
    suspend fun latest(repo: String, includePrereleases: Boolean): AppRelease? {
        val res = http.get("https://api.github.com/repos/$repo/releases") {
            expectSuccess = false
            header("Accept", "application/vnd.github+json")
            header("X-GitHub-Api-Version", "2022-11-28")
            parameter("per_page", 10)
        }
        if (!res.status.isSuccess()) {
            // Private or missing repos answer 404 to anonymous requests.
            throw AppException(AppException.Kind.NETWORK, "GitHub returned ${res.status.value}")
        }
        val list = json.parseToJsonElement(res.bodyAsText()) as? JsonArray ?: return null
        return list.asSequence()
            .mapNotNull { it as? JsonObject }
            .filter { it.bool("draft") != true && (includePrereleases || it.bool("prerelease") != true) }
            .mapNotNull(::parse)
            .firstOrNull()
    }

    suspend fun text(url: String): String? {
        val res = http.get(url) { expectSuccess = false }
        return if (res.status.isSuccess()) res.bodyAsText() else null
    }

    companion object {
        fun parse(o: JsonObject): AppRelease? {
            val tag = o.str("tag_name") ?: return null
            val assets = o.arr("assets").mapNotNull { it as? JsonObject }
            val apk = assets.firstOrNull { it.str("name")?.endsWith(".apk") == true } ?: return null
            val apkName = apk.str("name") ?: return null
            val url = apk.str("browser_download_url")?.takeIf { it.startsWith("https://") } ?: return null
            val digest = apk.str("digest")?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")?.lowercase()
            val shaAsset = assets.firstOrNull { it.str("name") == "$apkName.sha256" }
            return AppRelease(
                version = tag.removePrefix("v"),
                title = o.str("name")?.ifBlank { null } ?: tag,
                notes = cleanNotes(o.str("body").orEmpty()),
                pageUrl = o.str("html_url").orEmpty(),
                prerelease = o.bool("prerelease") == true,
                apkUrl = url,
                apkSize = apk.long("size") ?: 0,
                sha256 = digest,
                sha256Url = shaAsset?.str("browser_download_url"),
            )
        }

        /** GitHub's generated notes, minus link noise, readable as plain text. */
        fun cleanNotes(body: String): String = body.lines()
            .filterNot { it.startsWith("**Full Changelog**") }
            .map { line ->
                line.replace(Regex("""\s+by @[A-Za-z0-9-]+ in https://\S+"""), "")
                    .replace(Regex("^#+\\s*"), "")
                    .replace(Regex("^\\s*[*-]\\s+"), "• ")
                    .replace("**", "")
            }
            .joinToString("\n")
            .trim()

        /** -1, 0 or 1. "1.2.0" > "1.2.0-beta.1" > "1.1.9". Non-numeric parts compare as text. */
        fun compareVersions(a: String, b: String): Int {
            fun split(v: String): Pair<List<Int>, String?> {
                val clean = v.trim().removePrefix("v")
                val main = clean.substringBefore('-').substringBefore('+')
                val pre = clean.substringAfter('-', "").substringBefore('+').ifEmpty { null }
                return main.split('.').map { it.toIntOrNull() ?: 0 } to pre
            }
            val (na, pa) = split(a)
            val (nb, pb) = split(b)
            for (i in 0 until maxOf(na.size, nb.size)) {
                val c = (na.getOrElse(i) { 0 }).compareTo(nb.getOrElse(i) { 0 })
                if (c != 0) return c.coerceIn(-1, 1)
            }
            return when {
                pa == pb -> 0
                pa == null -> 1
                pb == null -> -1
                else -> pa.compareTo(pb).coerceIn(-1, 1)
            }
        }
    }
}
