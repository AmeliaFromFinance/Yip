package lol.sinist3r.yip.core.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import kotlinx.coroutines.runBlocking
import lol.sinist3r.yip.core.data.playback.PlaylistRewriter
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Wraps the normal HTTP source. HLS *media* playlists are read fully and passed through
 * [PlaylistRewriter] (ad detection, and opt-in ad handling). Segments stream untouched.
 */
@UnstableApi
class RewritingDataSource(
    private val upstream: DataSource,
    private val okHttp: OkHttpClient,
    private val rewriter: PlaylistRewriter,
) : BaseDataSource(true) {

    private var bytes: ByteArray? = null
    private var position = 0
    private var uri: Uri? = null
    private var delegating = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        val path = dataSpec.uri.path.orEmpty()
        if (!path.endsWith(".m3u8") || dataSpec.uri.host?.startsWith("usher.") == true) {
            delegating = true
            return upstream.open(dataSpec)
        }
        transferInitializing(dataSpec)
        val url = dataSpec.uri.toString()
        val body = okHttp.newCall(Request.Builder().url(url).build()).execute().use { res ->
            if (!res.isSuccessful) throw HttpDataSource.InvalidResponseCodeException(res.code, res.message, null, res.headers.toMultimap(), dataSpec, ByteArray(0))
            res.body.string()
        }
        val out = if ("#EXTINF" in body) runBlocking { rewriter.rewrite(url, body) } else body
        bytes = out.encodeToByteArray()
        position = dataSpec.position.toInt().coerceAtMost(bytes!!.size)
        transferStarted(dataSpec)
        val remaining = bytes!!.size - position
        return if (dataSpec.length != C.LENGTH_UNSET.toLong()) minOf(dataSpec.length, remaining.toLong()) else remaining.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (delegating) return upstream.read(buffer, offset, length)
        val data = bytes ?: return C.RESULT_END_OF_INPUT
        if (position >= data.size) return C.RESULT_END_OF_INPUT
        val n = minOf(length, data.size - position)
        System.arraycopy(data, position, buffer, offset, n)
        position += n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = if (delegating) upstream.uri else uri

    override fun close() {
        if (delegating) {
            upstream.close()
            delegating = false
        } else if (bytes != null) {
            bytes = null
            transferEnded()
        }
    }

    class Factory(private val upstream: DataSource.Factory, private val okHttp: OkHttpClient, private val rewriter: PlaylistRewriter) : DataSource.Factory {
        override fun createDataSource(): DataSource = RewritingDataSource(upstream.createDataSource(), okHttp, rewriter)
    }
}
