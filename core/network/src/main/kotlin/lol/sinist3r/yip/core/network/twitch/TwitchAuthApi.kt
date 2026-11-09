package lol.sinist3r.yip.core.network.twitch

import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.call.body
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.http.URLBuilder
import io.ktor.http.isSuccess
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import lol.sinist3r.yip.core.network.BuildConfig
import lol.sinist3r.yip.core.network.SnakeJson

@Serializable
data class DeviceCodeResponse(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val expiresIn: Int,
    val interval: Int,
)

@Serializable
data class TokenResponse(
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresIn: Long? = null,
    val scope: List<String> = emptyList(),
    val tokenType: String? = null,
)

@Serializable
data class ValidateResponse(
    val clientId: String,
    val login: String? = null,
    val userId: String? = null,
    val scopes: List<String> = emptyList(),
    val expiresIn: Long = 0,
)

sealed interface DevicePollResult {
    data class Success(val token: TokenResponse) : DevicePollResult
    data object Pending : DevicePollResult
    data object SlowDown : DevicePollResult
    data object Expired : DevicePollResult
    data class Denied(val message: String) : DevicePollResult
}

/**
 * Twitch OAuth for a public client: device code grant (primary) and implicit grant via a
 * verified App Link (fallback). No client secret ever ships in the app.
 */
@Singleton
class TwitchAuthApi @Inject constructor(
    private val http: HttpClient,
    @SnakeJson private val json: Json,
) {
    val clientId: String get() = BuildConfig.TWITCH_CLIENT_ID

    suspend fun startDeviceFlow(scopes: List<String>): DeviceCodeResponse {
        val res = http.submitForm(
            "https://id.twitch.tv/oauth2/device",
            Parameters.build {
                append("client_id", clientId)
                append("scopes", scopes.joinToString(" "))
            },
        )
        return json.decodeFromString(res.bodyAsText())
    }

    suspend fun pollDeviceFlow(deviceCode: String, scopes: List<String>): DevicePollResult {
        val res = postToken(
            Parameters.build {
                append("client_id", clientId)
                append("scopes", scopes.joinToString(" "))
                append("device_code", deviceCode)
                append("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
            },
        )
        val text = res.bodyAsText()
        if (res.status.isSuccess()) return DevicePollResult.Success(json.decodeFromString(text))
        return when {
            "authorization_pending" in text -> DevicePollResult.Pending
            "slow_down" in text -> DevicePollResult.SlowDown
            "expired" in text || "invalid device code" in text -> DevicePollResult.Expired
            else -> DevicePollResult.Denied(text.take(200))
        }
    }

    /** Public clients may refresh without a secret. Returns null if the refresh token is dead. */
    suspend fun refresh(refreshToken: String): TokenResponse? {
        val res = postToken(
            Parameters.build {
                append("client_id", clientId)
                append("grant_type", "refresh_token")
                append("refresh_token", refreshToken)
            },
        )
        return if (res.status.isSuccess()) json.decodeFromString(res.bodyAsText()) else null
    }

    suspend fun validate(token: String): ValidateResponse? {
        val res = http.get("https://id.twitch.tv/oauth2/validate") {
            header(HttpHeaders.Authorization, "OAuth $token")
            expectSuccess = false
        }
        return if (res.status.isSuccess()) json.decodeFromString(res.bodyAsText()) else null
    }

    suspend fun revoke(token: String) {
        http.submitForm(
            "https://id.twitch.tv/oauth2/revoke",
            Parameters.build {
                append("client_id", clientId)
                append("token", token)
            },
        ) { expectSuccess = false }
    }

    /** Implicit grant URL. [state] must be checked on return to block CSRF. */
    fun implicitAuthorizeUrl(scopes: List<String>, state: String): String =
        URLBuilder("https://id.twitch.tv/oauth2/authorize").apply {
            parameters.append("response_type", "token")
            parameters.append("client_id", clientId)
            parameters.append("redirect_uri", BuildConfig.AUTH_REDIRECT_URI)
            parameters.append("scope", scopes.joinToString(" "))
            parameters.append("state", state)
            parameters.append("force_verify", "true")
        }.buildString()

    fun newState(): String {
        val bytes = ByteArray(24).also(SecureRandom()::nextBytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private suspend fun postToken(params: Parameters): HttpResponse =
        http.submitForm("https://id.twitch.tv/oauth2/token", params) { expectSuccess = false }

    companion object {
        val SCOPES = listOf(
            "user:read:chat",
            "user:write:chat",
            "user:read:follows",
            "user:read:emotes",
            "user:read:blocked_users",
            "user:manage:blocked_users",
            "user:read:subscriptions",
            "user:manage:chat_color",
            "user:read:whispers",
            "user:manage:whispers",
            "channel:read:polls",
            "channel:read:predictions",
            "channel:read:redemptions",
            "moderator:manage:banned_users",
            "moderator:manage:chat_messages",
            "moderator:manage:chat_settings",
            "moderator:manage:announcements",
            "moderator:read:chatters",
            "moderator:manage:shoutouts",
            "clips:edit",
            // Stream manager
            "channel:manage:broadcast",
            "channel:edit:commercial",
            "channel:read:ads",
            "channel:manage:ads",
            "moderator:manage:automod",
            "moderator:manage:unban_requests",
        )

        /** Scopes the stream manager needs; older sign-ins don't have them. */
        val STREAM_MANAGER_SCOPES = listOf(
            "channel:manage:broadcast", "channel:edit:commercial", "channel:read:ads", "channel:manage:ads",
            "moderator:read:chatters", "moderator:manage:automod", "moderator:manage:unban_requests",
        )
    }
}
