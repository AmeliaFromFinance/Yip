package dev.yipchat.core.network

/** Supplies the active user's Helix token. Implemented in the data layer. */
interface TokenProvider {
    /** Current token, or null when signed out. */
    suspend fun accessToken(): String?

    /** Called after a 401. Returns a fresh token or null if the session is gone. */
    suspend fun refresh(): String?

    suspend fun userId(): String?
}

/** Supplies the optional twitch.tv web token used for GQL-only features. */
interface WebTokenProvider {
    suspend fun webToken(): String?
    /** Client the web token was issued to; GQL must send the same one. */
    suspend fun webClientId(): String? = null
    suspend fun integrityToken(forceRefresh: Boolean = false): IntegrityToken?
}

data class IntegrityToken(val token: String, val deviceId: String, val expiresAtMillis: Long)
