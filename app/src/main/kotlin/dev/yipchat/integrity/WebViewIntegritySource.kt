package dev.yipchat.integrity

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.yipchat.core.data.account.IntegrityTokenSource
import dev.yipchat.core.network.IntegrityToken
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.json.JSONObject
import timber.log.Timber

/**
 * Gets a Twitch client-integrity token the same way Streamlink does: load twitch.tv in a
 * headless WebView so Twitch's anti-bot script runs, then call the integrity endpoint from
 * inside the page. Used only for experimental GQL mutations (points, follow).
 */
@Singleton
class WebViewIntegritySource @Inject constructor(@ApplicationContext private val context: Context) : IntegrityTokenSource {

    @SuppressLint("SetJavaScriptEnabled")
    override suspend fun fetch(webToken: String?, clientId: String, deviceId: String): IntegrityToken? = withContext(Dispatchers.Main) {
        withTimeoutOrNull(30_000) {
            suspendCancellableCoroutine { cont ->
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                    cont.resume(null)
                    return@suspendCancellableCoroutine
                }
                val web = WebView(context)
                web.settings.javaScriptEnabled = true
                web.settings.domStorageEnabled = true
                cont.invokeOnCancellation { web.destroy() }
                // Only pages from twitch.tv can post back, and only once.
                WebViewCompat.addWebMessageListener(web, "yipBridge", setOf("https://www.twitch.tv")) { _, message, _, _, _ ->
                    val token = runCatching {
                        val o = Json.parseToJsonElement(message.data.orEmpty()).jsonObject
                        IntegrityToken(o["token"]!!.jsonPrimitive.content, deviceId, o["expiration"]!!.jsonPrimitive.long)
                    }.onFailure { Timber.w(it, "Integrity token unavailable") }.getOrNull()
                    web.destroy()
                    if (cont.isActive) cont.resume(token)
                }
                web.webViewClient = object : WebViewClient() {
                    var done = false
                    override fun onPageFinished(view: WebView, url: String?) {
                        if (done) return
                        done = true
                        val headers = JSONObject().apply {
                            put("Client-Id", clientId)
                            put("X-Device-Id", deviceId)
                            webToken?.let { put("Authorization", "OAuth $it") }
                        }
                        view.evaluateJavascript(
                            """
                            fetch("https://gql.twitch.tv/integrity", { method: "POST", headers: $headers })
                              .then(r => r.json()).then(j => yipBridge.postMessage(JSON.stringify(j)))
                              .catch(() => yipBridge.postMessage("{}"));
                            """.trimIndent(),
                            null,
                        )
                    }
                }
                web.loadUrl("https://www.twitch.tv/")
            }
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class IntegrityModule {
    @Binds abstract fun source(impl: WebViewIntegritySource): IntegrityTokenSource
}
