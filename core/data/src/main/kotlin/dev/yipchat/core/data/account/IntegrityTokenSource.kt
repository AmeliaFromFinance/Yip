package dev.yipchat.core.data.account

import dev.yipchat.core.network.IntegrityToken

/**
 * Produces a Twitch client-integrity token. Needs a real browser engine, so the app module
 * provides a WebView-backed implementation.
 */
interface IntegrityTokenSource {
    suspend fun fetch(webToken: String?, clientId: String, deviceId: String): IntegrityToken?
}
