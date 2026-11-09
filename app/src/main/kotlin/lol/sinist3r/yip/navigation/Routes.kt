package lol.sinist3r.yip.navigation

import kotlinx.serialization.Serializable

@Serializable data object FollowingRoute
@Serializable data object BrowseRoute
@Serializable data object SearchRoute
@Serializable data object SettingsRoute
@Serializable data class SettingsPageRoute(val key: String)
@Serializable data class CategoryRoute(val id: String, val name: String)
@Serializable data class ChannelRoute(val login: String)

/** kind = LIVE | VOD | CLIP; id = login, video id or clip slug. */
@Serializable data class WatchRoute(
    val kind: String,
    val id: String,
    val offset: Int = -1,
    val channelLogin: String? = null,
    val title: String? = null,
    val vodId: String? = null,
)

@Serializable data class MultiViewRoute(val logins: String)
@Serializable data class LibraryRoute(val tab: Int = 0)
@Serializable data object InboxRoute
@Serializable data class WhisperRoute(val login: String)
@Serializable data object LoginRoute
@Serializable data object ConnectWebRoute
@Serializable data object LogsRoute
@Serializable data object StreamManagerRoute
