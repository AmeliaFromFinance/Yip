package dev.yipchat.navigation

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
@Serializable data object LibraryRoute
@Serializable data object DataUsageRoute
@Serializable data object ManageFollowsRoute
@Serializable data object FollowsClipsRoute
@Serializable data object InboxRoute
@Serializable data class WhisperRoute(val login: String)
@Serializable data object LoginRoute
/** [afterLogin]: shown right after Accounts sign-in, with a skip option. */
@Serializable data class ConnectWebRoute(val afterLogin: Boolean = false)
@Serializable data object LogsRoute
@Serializable data object StreamManagerRoute
