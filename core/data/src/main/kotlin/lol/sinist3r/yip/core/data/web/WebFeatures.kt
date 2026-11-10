package lol.sinist3r.yip.core.data.web

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import lol.sinist3r.yip.core.common.attempt
import lol.sinist3r.yip.core.data.account.AccountRepository
import lol.sinist3r.yip.core.model.FollowedCategory
import lol.sinist3r.yip.core.network.twitch.GqlApi

/** Followed categories. Needs the twitch.tv connection. */
@Singleton
class WebFeatures @Inject constructor(private val gql: GqlApi, private val accounts: AccountRepository) {
    private val _categories = MutableStateFlow<List<FollowedCategory>>(emptyList())
    val followedCategories: StateFlow<List<FollowedCategory>> = _categories.asStateFlow()

    suspend fun connected() = accounts.webToken() != null

    suspend fun refreshCategories() {
        if (!connected()) { _categories.value = emptyList(); return }
        attempt { gql.followedGames() }.onSuccess { _categories.value = it }
    }

    /** Null on success, otherwise why it failed. Updates the list straight away. */
    suspend fun setFollowingCategory(category: FollowedCategory, follow: Boolean): String? {
        if (!connected()) return CONNECT
        val before = _categories.value
        _categories.update { list -> if (follow) (list.filterNot { it.id == category.id } + category) else list.filterNot { it.id == category.id } }
        return attempt { gql.setFollowingGame(category.id, follow) }.fold({ refreshCategories(); null }, {
            _categories.value = before
            it.message ?: "Couldn't update"
        })
    }

    private companion object { const val CONNECT = "Connect twitch.tv under Settings → Accounts first" }
}
