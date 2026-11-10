package dev.yipchat.core.data.settings

import androidx.datastore.core.DataStore
import dev.yipchat.core.common.AppScope
import dev.yipchat.core.datastore.SettingsJson
import dev.yipchat.core.datastore.SettingsTransfer
import dev.yipchat.core.model.AppSettings
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn

@Singleton
class SettingsRepository @Inject constructor(
    private val store: DataStore<AppSettings>,
    @AppScope scope: CoroutineScope,
) {
    val settings: StateFlow<AppSettings> = store.data.stateIn(scope, SharingStarted.Eagerly, AppSettings())

    suspend fun current(): AppSettings = store.data.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.updateData(transform)
    }

    suspend fun reset() = store.updateData { AppSettings(onboardingDone = it.onboardingDone) }

    suspend fun exportJson(): String = SettingsJson.export(current())

    /** Link for the QR code, or null when the settings are too big for one. */
    suspend fun transferLink(): String? {
        val payload = SettingsTransfer.encode(current())
        return if (payload.length <= SettingsTransfer.QR_MAX_PAYLOAD) SettingsTransfer.LINK_PREFIX + payload else null
    }

    /** Decodes a transfer link's payload without applying it. Throws if it isn't one. */
    fun readTransfer(payload: String): AppSettings = SettingsTransfer.decode(payload)

    suspend fun apply(imported: AppSettings) {
        store.updateData { imported.copy(onboardingDone = true) }
    }

    /** Throws on invalid input; caller shows the error. */
    suspend fun importJson(text: String) {
        val imported = SettingsJson.import(text)
        store.updateData { imported.copy(onboardingDone = true) }
    }
}
