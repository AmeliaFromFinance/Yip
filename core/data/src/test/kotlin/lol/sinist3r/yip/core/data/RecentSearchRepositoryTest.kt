package lol.sinist3r.yip.core.data

import androidx.datastore.core.DataStore
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import lol.sinist3r.yip.core.data.search.RecentSearchRepository
import lol.sinist3r.yip.core.data.settings.SettingsRepository
import lol.sinist3r.yip.core.model.AppSettings
import lol.sinist3r.yip.core.model.RecentKind
import lol.sinist3r.yip.core.model.RecentSearch
import lol.sinist3r.yip.core.model.RecentSearches
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentSearchRepositoryTest {
    private class MemoryStore : DataStore<RecentSearches> {
        val state = MutableStateFlow(RecentSearches())
        override val data = state
        override suspend fun updateData(transform: suspend (RecentSearches) -> RecentSearches) = transform(state.value).also { state.value = it }
    }

    private val config = MutableStateFlow(AppSettings())
    private val store = MemoryStore()
    private val repo = RecentSearchRepository(store, mockk<SettingsRepository> { every { settings } returns config })

    private fun ch(login: String) = RecentSearch(RecentKind.CHANNEL, login, login)

    @Test fun newestFirstAndNoDuplicates() = runBlocking {
        repo.add(ch("a")); repo.add(ch("b")); repo.add(ch("A"))
        assertEquals(listOf("A", "b"), repo.items.first().map { it.key })
    }

    @Test fun capped() = runBlocking {
        repeat(40) { repo.add(ch("c$it")) }
        assertEquals(25, store.state.value.items.size)
        assertEquals("c39", store.state.value.items.first().key)
    }

    @Test fun offMeansNothingSavedOrShown() = runBlocking {
        repo.add(ch("a"))
        config.value = config.value.copy(data = config.value.data.copy(keepRecentSearches = false))
        repo.add(ch("b"))
        assertTrue(repo.items.first().isEmpty())
        assertEquals(listOf("a"), store.state.value.items.map { it.key })
    }
}
