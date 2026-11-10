package dev.yipchat.core.data.search

import androidx.datastore.core.DataStore
import dev.yipchat.core.data.settings.SettingsRepository
import dev.yipchat.core.model.RecentSearch
import dev.yipchat.core.model.RecentSearches
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** Recent channels, categories and queries from search, newest first. Stays on the device. */
@Singleton
class RecentSearchRepository @Inject constructor(
    private val store: DataStore<RecentSearches>,
    private val settings: SettingsRepository,
) {
    val items: Flow<List<RecentSearch>> = combine(store.data, settings.settings) { s, cfg ->
        if (cfg.data.keepRecentSearches) s.items else emptyList()
    }

    suspend fun add(item: RecentSearch) {
        val s = settings.settings.value
        if (!s.data.keepRecentSearches || s.general.incognito || item.key.isBlank()) return
        store.updateData { s ->
            val rest = s.items.filterNot { it.kind == item.kind && it.key.equals(item.key, ignoreCase = true) }
            RecentSearches((listOf(item) + rest).take(MAX))
        }
    }

    suspend fun remove(item: RecentSearch) =
        store.updateData { s -> RecentSearches(s.items.filterNot { it.kind == item.kind && it.key.equals(item.key, ignoreCase = true) }) }

    suspend fun clear() = store.updateData { RecentSearches() }

    private companion object {
        const val MAX = 25
    }
}
