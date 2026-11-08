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

    /** Throws on invalid input; caller shows the error. */
    suspend fun importJson(text: String) {
        val imported = SettingsJson.import(text)
        store.updateData { imported.copy(onboardingDone = true) }
    }
}
