package lol.sinist3r.yip.core.data.settings

import androidx.datastore.core.DataStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import lol.sinist3r.yip.core.common.AppScope
import lol.sinist3r.yip.core.datastore.SettingsJson
import lol.sinist3r.yip.core.model.AppSettings

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
        val payload = lol.sinist3r.yip.core.datastore.SettingsTransfer.encode(current())
        return if (payload.length <= lol.sinist3r.yip.core.datastore.SettingsTransfer.QR_MAX_PAYLOAD) lol.sinist3r.yip.core.datastore.SettingsTransfer.LINK_PREFIX + payload else null
    }

    /** Decodes a transfer link's payload without applying it. Throws if it isn't one. */
    fun readTransfer(payload: String): AppSettings = lol.sinist3r.yip.core.datastore.SettingsTransfer.decode(payload)

    suspend fun apply(imported: AppSettings) {
        store.updateData { imported.copy(onboardingDone = true) }
    }

    /** Throws on invalid input; caller shows the error. */
    suspend fun importJson(text: String) {
        val imported = SettingsJson.import(text)
        store.updateData { imported.copy(onboardingDone = true) }
    }
}
