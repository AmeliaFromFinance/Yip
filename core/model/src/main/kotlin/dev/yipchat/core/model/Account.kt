package dev.yipchat.core.model

import kotlinx.serialization.Serializable

/** A signed-in Twitch account. Tokens live in [AccountSecrets], never in plain settings. */
@Serializable
data class Account(
    val userId: String,
    val login: String,
    val displayName: String,
    val profileImageUrl: String? = null,
    val scopes: List<String> = emptyList(),
    val hasWebToken: Boolean = false,
)

@Serializable
data class AccountSecrets(
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresAtEpochSec: Long? = null,
    /** Optional twitch.tv web token for GQL-only features (points, predictions). */
    val webToken: String? = null,
    val webClientId: String? = null,
)

@Serializable
data class AccountsState(
    val accounts: List<Account> = emptyList(),
    val activeUserId: String? = null,
) {
    val active: Account? get() = accounts.firstOrNull { it.userId == activeUserId }
}
