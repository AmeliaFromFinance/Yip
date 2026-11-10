package dev.yipchat.core.datastore

import dev.yipchat.core.model.AppSettings

/** Import/export of settings. Never includes account data. */
object SettingsJson {
    fun export(settings: AppSettings): String = storeJson.encodeToString(AppSettings.serializer(), settings)
    fun import(text: String): AppSettings = storeJson.decodeFromString(AppSettings.serializer(), text)
}
