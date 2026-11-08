package lol.sinist3r.yip.core.datastore

import lol.sinist3r.yip.core.model.AppSettings

/** Import/export of settings. Never includes account data. */
object SettingsJson {
    fun export(settings: AppSettings): String = storeJson.encodeToString(AppSettings.serializer(), settings)
    fun import(text: String): AppSettings = storeJson.decodeFromString(AppSettings.serializer(), text)
}
