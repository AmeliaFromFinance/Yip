package lol.sinist3r.yip.core.datastore

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlinx.serialization.json.Json
import lol.sinist3r.yip.core.model.AppSettings

/** Settings packed small enough for a QR code: only changed values, deflated, base64url. */
object SettingsTransfer {
    const val LINK_PREFIX = "https://yip.sinist3r.lol/import#"
    /** Byte-mode capacity of the largest QR code at low error correction, minus the link prefix. */
    const val QR_MAX_PAYLOAD = 2953 - LINK_PREFIX.length

    private val compact = Json { encodeDefaults = false; ignoreUnknownKeys = true; explicitNulls = false }

    fun encode(settings: AppSettings): String {
        val raw = compact.encodeToString(AppSettings.serializer(), settings).encodeToByteArray()
        val d = Deflater(Deflater.BEST_COMPRESSION, true).apply { setInput(raw); finish() }
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end()
        return Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray())
    }

    /** Throws on anything that isn't a Yip transfer. */
    fun decode(payload: String): AppSettings {
        val bytes = Base64.getUrlDecoder().decode(payload.trim())
        val inf = Inflater(true).apply { setInput(bytes) }
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!inf.finished()) {
            val n = inf.inflate(buf)
            if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
            out.write(buf, 0, n)
            require(out.size() < 2_000_000) { "Too large" }
        }
        inf.end()
        return compact.decodeFromString(AppSettings.serializer(), out.toByteArray().decodeToString())
    }

    fun link(settings: AppSettings): String = LINK_PREFIX + encode(settings)
}
