package dev.yipchat.core.network

import dev.yipchat.core.model.RemoteConfig
import dev.yipchat.core.network.config.ConfigSignature
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The fixture was produced by watcher/yip_watcher.py, so this proves Python and Kotlin agree. */
class ConfigSignatureTest {
    private val fixture = Json.parseToJsonElement(javaClass.classLoader!!.getResource("signed_config.json")!!.readText()).jsonObject
    private val pub = fixture["publicKey"]!!.jsonPrimitive.content
    private val env = fixture["envelope"]!!.jsonObject
    private val payload = Base64.getDecoder().decode(env["payload"]!!.jsonPrimitive.content)
    private val sig = env["sig"]!!.jsonPrimitive.content

    @Test
    fun `accepts watcher signature`() {
        assertTrue(ConfigSignature.verify(pub, payload, sig))
        val cfg = Json { ignoreUnknownKeys = true }.decodeFromString(RemoteConfig.serializer(), payload.decodeToString())
        assertEquals(RemoteConfig.DEFAULT_WEB_CLIENT_ID, cfg.clientId)
        assertTrue(cfg.query("PlaybackAccessToken_Template")!!.contains("streamPlaybackAccessToken"))
    }

    @Test
    fun `rejects tampered payload`() {
        val tampered = payload.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        assertFalse(ConfigSignature.verify(pub, tampered, sig))
    }

    @Test
    fun `rejects wrong key and garbage`() {
        val otherKey = Base64.getEncoder().encodeToString(ByteArray(32) { 7 })
        assertFalse(ConfigSignature.verify(otherKey, payload, sig))
        assertFalse(ConfigSignature.verify("not base64!", payload, sig))
    }
}
