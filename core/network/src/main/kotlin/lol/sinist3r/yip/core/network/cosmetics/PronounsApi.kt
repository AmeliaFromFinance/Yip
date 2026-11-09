package lol.sinist3r.yip.core.network.cosmetics

import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import lol.sinist3r.yip.core.common.AppException
import lol.sinist3r.yip.core.network.twitch.bool
import lol.sinist3r.yip.core.network.twitch.str

data class PronounDef(val subject: String, val objective: String, val singular: Boolean)

/** pronouns.alejo.io, the service behind the popular Twitch pronouns extension. */
@Singleton
class PronounsApi @Inject constructor(private val http: HttpClient, private val json: Json) {

    suspend fun catalogue(): Map<String, PronounDef> {
        val o = getJson("https://api.pronouns.alejo.io/v1/pronouns") ?: return emptyMap()
        return o.mapNotNull { (id, v) ->
            val d = v as? JsonObject ?: return@mapNotNull null
            id to PronounDef(d.str("subject") ?: return@mapNotNull null, d.str("object").orEmpty(), d.bool("singular") == true)
        }.toMap()
    }

    /** (primary, alt) pronoun ids, or null when the user hasn't set any. */
    suspend fun user(login: String): Pair<String, String?>? {
        require(login.isNotEmpty() && login.all { it.isLetterOrDigit() || it == '_' }) { "bad login" }
        val o = getJson("https://api.pronouns.alejo.io/v1/users/${login.lowercase()}") ?: return null
        return (o.str("pronoun_id") ?: return null) to o.str("alt_pronoun_id")
    }

    private suspend fun getJson(url: String): JsonObject? {
        val res = http.get(url) { expectSuccess = false }
        if (res.status == HttpStatusCode.NotFound) return null
        if (!res.status.isSuccess()) throw AppException(AppException.Kind.NETWORK, "Pronouns returned ${res.status.value}")
        return json.parseToJsonElement(res.bodyAsText()) as? JsonObject
    }

    companion object {
        /** "She/They" with an alt, "Any" for singular ones, otherwise "He/Him". */
        fun label(primary: String, alt: String?, defs: Map<String, PronounDef>): String? {
            val p = defs[primary] ?: return null
            val a = alt?.let(defs::get)
            return when {
                a != null -> "${p.subject}/${a.subject}"
                p.singular || p.objective.isBlank() -> p.subject
                else -> "${p.subject}/${p.objective}"
            }
        }
    }
}
