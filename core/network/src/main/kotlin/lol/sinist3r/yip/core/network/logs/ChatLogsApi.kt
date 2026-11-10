package lol.sinist3r.yip.core.network.logs

import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/** logs.ivr.fi, a public Twitch chat log (only channels it tracks). */
@Singleton
class ChatLogsApi @Inject constructor(private val http: HttpClient, private val json: Json) {

    sealed interface Result {
        /** Raw IRC lines, newest first. */
        data class Lines(val lines: List<String>) : Result
        data object NotLogged : Result
    }

    suspend fun userLines(channelLogin: String, userLogin: String, limit: Int = 100): Result {
        require(listOf(channelLogin, userLogin).all { l -> l.isNotEmpty() && l.all { it.isLetterOrDigit() || it == '_' } }) { "bad login" }
        val res = http.get("https://logs.ivr.fi/channel/${channelLogin.lowercase()}/user/${userLogin.lowercase()}") {
            expectSuccess = false
            parameter("json", 1)
            parameter("reverse", 1)
            parameter("limit", limit)
        }
        if (res.status == HttpStatusCode.NotFound || res.status == HttpStatusCode.Forbidden) return Result.NotLogged
        check(res.status.isSuccess()) { "Chat logs returned ${res.status.value}" }
        return Result.Lines(parse(json, res.bodyAsText()).take(limit))
    }

    companion object {
        internal fun parse(json: Json, body: String): List<String> =
            ((json.parseToJsonElement(body) as? JsonObject)?.get("messages") as? kotlinx.serialization.json.JsonArray).orEmpty()
                .mapNotNull { (it as? JsonObject)?.get("raw")?.jsonPrimitive?.content }
    }
}
