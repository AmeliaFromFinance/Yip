package lol.sinist3r.yip.core.data.account

import androidx.datastore.core.DataStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import lol.sinist3r.yip.core.common.AppScope
import lol.sinist3r.yip.core.common.attempt
import lol.sinist3r.yip.core.datastore.SecretsFile
import lol.sinist3r.yip.core.model.Account
import lol.sinist3r.yip.core.model.AccountSecrets
import lol.sinist3r.yip.core.model.AccountsState
import lol.sinist3r.yip.core.network.IntegrityToken
import lol.sinist3r.yip.core.network.TokenProvider
import lol.sinist3r.yip.core.network.WebTokenProvider
import lol.sinist3r.yip.core.network.config.RemoteConfigSource
import lol.sinist3r.yip.core.network.twitch.DevicePollResult
import lol.sinist3r.yip.core.network.twitch.HelixApi
import lol.sinist3r.yip.core.network.twitch.TokenResponse
import lol.sinist3r.yip.core.network.twitch.TwitchAuthApi
import timber.log.Timber
import dagger.Lazy

sealed interface DeviceLoginState {
    data object Starting : DeviceLoginState
    data class WaitingForUser(val userCode: String, val verificationUri: String, val expiresAtMillis: Long) : DeviceLoginState
    data class Success(val account: Account) : DeviceLoginState
    data class Failed(val reason: String) : DeviceLoginState
}

/**
 * Accounts, token storage and refresh. Implements the network layer's token providers.
 * Multiple accounts are supported; one is active at a time.
 */
@Singleton
class AccountRepository @Inject constructor(
    private val accountsStore: DataStore<AccountsState>,
    private val secretsStore: DataStore<SecretsFile>,
    private val auth: TwitchAuthApi,
    private val helix: Lazy<HelixApi>,
    private val config: RemoteConfigSource,
    private val integritySource: Lazy<IntegrityTokenSource>,
    @AppScope scope: CoroutineScope,
) : TokenProvider, WebTokenProvider {

    private val refreshMutex = Mutex()
    private var integrity: IntegrityToken? = null

    val state: StateFlow<AccountsState> = accountsStore.data.stateIn(scope, SharingStarted.Eagerly, AccountsState())
    val active: Flow<Account?> = accountsStore.data.map { it.active }
    val isSignedIn: Flow<Boolean> = active.map { it != null }

    private suspend fun activeId(): String? = accountsStore.data.first().activeUserId
    private suspend fun secrets(userId: String): AccountSecrets? = secretsStore.data.first().byUserId[userId]

    // region TokenProvider

    override suspend fun accessToken(): String? = activeId()?.let { secrets(it)?.accessToken }

    override suspend fun userId(): String? = activeId()

    override suspend fun refresh(): String? = refreshMutex.withLock {
        val id = activeId() ?: return null
        val s = secrets(id) ?: return null
        // Another caller may already have refreshed while we waited.
        auth.validate(s.accessToken)?.let { return s.accessToken }
        val refreshed = s.refreshToken?.let { auth.refresh(it) }
        if (refreshed == null) {
            Timber.i("Session expired for %s", id)
            return null
        }
        saveSecrets(id, s.copy(accessToken = refreshed.accessToken, refreshToken = refreshed.refreshToken ?: s.refreshToken, expiresAtEpochSec = refreshed.expiresAt()))
        refreshed.accessToken
    }

    // endregion

    // region WebTokenProvider (experimental GQL features)

    override suspend fun webToken(): String? = activeId()?.let { secrets(it)?.webToken }
    override suspend fun webClientId(): String? = activeId()?.let { secrets(it)?.webClientId }

    @Volatile private var integrityFailedAt = 0L

    override suspend fun integrityToken(forceRefresh: Boolean): IntegrityToken? {
        val cached = integrity
        if (!forceRefresh && cached != null && cached.expiresAtMillis > System.currentTimeMillis() + 30_000) return cached
        // A failed fetch can take the full timeout; don't repeat it on every action.
        if (!forceRefresh && System.currentTimeMillis() - integrityFailedAt < INTEGRITY_RETRY_MS) return null
        val cfg = config.current()
        return integritySource.get().fetch(webToken(), webClientId() ?: cfg.clientId, deviceId = cached?.deviceId ?: randomDeviceId())
            .also { integrity = it; if (it == null) integrityFailedAt = System.currentTimeMillis() }
    }

    private fun randomDeviceId(): String = java.util.UUID.randomUUID().toString().replace("-", "")

    // endregion

    // region login flows

    fun deviceLogin(): Flow<DeviceLoginState> = flow {
        emit(DeviceLoginState.Starting)
        val start = attempt { auth.startDeviceFlow(TwitchAuthApi.SCOPES) }.getOrElse {
            emit(DeviceLoginState.Failed("Couldn't reach Twitch. Check your connection."))
            return@flow
        }
        val expiresAt = System.currentTimeMillis() + start.expiresIn * 1000L
        emit(DeviceLoginState.WaitingForUser(start.userCode, start.verificationUri, expiresAt))
        var interval = start.interval.coerceAtLeast(1)
        while (System.currentTimeMillis() < expiresAt) {
            delay(interval.seconds)
            when (val r = attempt { auth.pollDeviceFlow(start.deviceCode, TwitchAuthApi.SCOPES) }.getOrNull()) {
                is DevicePollResult.Success -> {
                    val account = completeLogin(r.token)
                    emit(account?.let(DeviceLoginState::Success) ?: DeviceLoginState.Failed("Signed in, but couldn't load your profile."))
                    return@flow
                }
                DevicePollResult.SlowDown -> interval += 5
                DevicePollResult.Expired -> break
                is DevicePollResult.Denied -> { emit(DeviceLoginState.Failed("Login was declined.")); return@flow }
                DevicePollResult.Pending, null -> Unit
            }
        }
        emit(DeviceLoginState.Failed("The code expired. Try again."))
    }

    /**
     * Connects twitch.tv with a code entered at twitch.tv/activate, using Twitch's TV app client.
     * Must be the same Twitch account Yip is signed in with.
     */
    fun webDeviceLogin(): Flow<DeviceLoginState> = flow {
        emit(DeviceLoginState.Starting)
        val client = TwitchAuthApi.TV_CLIENT_ID
        val start = attempt { auth.startDeviceFlow(TwitchAuthApi.TV_SCOPES, client) }.getOrElse {
            emit(DeviceLoginState.Failed("Couldn't reach Twitch. Check your connection."))
            return@flow
        }
        val expiresAt = System.currentTimeMillis() + start.expiresIn * 1000L
        emit(DeviceLoginState.WaitingForUser(start.userCode, start.verificationUri, expiresAt))
        var interval = start.interval.coerceAtLeast(1)
        while (System.currentTimeMillis() < expiresAt) {
            delay(interval.seconds)
            when (val r = attempt { auth.pollDeviceFlow(start.deviceCode, TwitchAuthApi.TV_SCOPES, client) }.getOrNull()) {
                is DevicePollResult.Success -> {
                    val v = attempt { auth.validate(r.token.accessToken) }.getOrNull()
                    val active = state.value.active
                    when {
                        v == null -> emit(DeviceLoginState.Failed("Twitch didn't accept the login. Try again."))
                        active != null && v.userId != active.userId ->
                            emit(DeviceLoginState.Failed("That code was used by ${v.login}, but Yip is signed in as ${active.login}. Use the same account."))
                        else -> {
                            connectWebToken(r.token.accessToken, client)
                            emit(active?.let(DeviceLoginState::Success) ?: DeviceLoginState.Failed("Sign in to Yip first."))
                        }
                    }
                    return@flow
                }
                DevicePollResult.SlowDown -> interval += 5
                DevicePollResult.Expired -> break
                is DevicePollResult.Denied -> { emit(DeviceLoginState.Failed("Login was declined.")); return@flow }
                DevicePollResult.Pending, null -> Unit
            }
        }
        emit(DeviceLoginState.Failed("The code expired. Try again."))
    }

    @Volatile private var pendingBrowserState: String? = null

    /** Starts the browser (implicit grant) flow; returns the URL to open in a Custom Tab. */
    fun beginBrowserLogin(): String {
        val state = auth.newState()
        pendingBrowserState = state
        return auth.implicitAuthorizeUrl(TwitchAuthApi.SCOPES, state)
    }

    /** Called with the redirect's URL fragment. Returns null on mismatch or failure. */
    suspend fun completeBrowserLogin(fragment: String): Account? {
        val expected = pendingBrowserState ?: return null
        pendingBrowserState = null
        return completeImplicitLogin(fragment, expected)
    }

    /** Completes the implicit-grant browser flow. [state] must match what we sent. */
    suspend fun completeImplicitLogin(fragment: String, expectedState: String): Account? {
        val params = fragment.removePrefix("#").split('&').associate { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        if (params["state"] != expectedState) {
            Timber.w("OAuth state mismatch, ignoring redirect")
            return null
        }
        val token = params["access_token"] ?: return null
        return completeLogin(TokenResponse(accessToken = token, scope = params["scope"]?.split(' ').orEmpty()))
    }

    private suspend fun completeLogin(token: TokenResponse): Account? {
        val valid = auth.validate(token.accessToken) ?: return null
        val userId = valid.userId ?: return null
        saveSecrets(userId, AccountSecrets(token.accessToken, token.refreshToken, token.expiresAt()))
        val previous = accountsStore.data.first().activeUserId
        accountsStore.updateData { s ->
            val placeholder = Account(userId, valid.login.orEmpty(), valid.login.orEmpty(), scopes = valid.scopes)
            s.copy(accounts = s.accounts.filterNot { it.userId == userId } + placeholder, activeUserId = userId)
        }
        val user = attempt { helix.get().currentUser() }.getOrNull()
        val account = Account(
            userId = userId,
            login = user?.login ?: valid.login.orEmpty(),
            displayName = user?.displayName ?: valid.login.orEmpty(),
            profileImageUrl = user?.profileImageUrl,
            scopes = valid.scopes,
            hasWebToken = secrets(userId)?.webToken != null,
        )
        accountsStore.updateData { s -> s.copy(accounts = s.accounts.map { if (it.userId == userId) account else it }) }
        Timber.i("Signed in %s (previous %s)", account.login, previous)
        return account
    }

    // endregion

    suspend fun switchTo(userId: String) {
        accountsStore.updateData { it.copy(activeUserId = userId.takeIf { id -> it.accounts.any { a -> a.userId == id } }) }
        integrity = null
    }

    suspend fun signOut(userId: String) {
        secrets(userId)?.accessToken?.let { attempt { auth.revoke(it) } }
        secretsStore.updateData { it.copy(byUserId = it.byUserId - userId) }
        accountsStore.updateData { s ->
            val remaining = s.accounts.filterNot { it.userId == userId }
            s.copy(accounts = remaining, activeUserId = if (s.activeUserId == userId) remaining.firstOrNull()?.userId else s.activeUserId)
        }
    }

    suspend fun connectWebToken(token: String, clientId: String?) {
        val id = activeId() ?: return
        val s = secrets(id) ?: return
        saveSecrets(id, s.copy(webToken = token, webClientId = clientId))
        accountsStore.updateData { st -> st.copy(accounts = st.accounts.map { if (it.userId == id) it.copy(hasWebToken = true) else it }) }
    }

    suspend fun disconnectWebToken() {
        val id = activeId() ?: return
        val s = secrets(id) ?: return
        saveSecrets(id, s.copy(webToken = null, webClientId = null))
        accountsStore.updateData { st -> st.copy(accounts = st.accounts.map { if (it.userId == id) it.copy(hasWebToken = false) else it }) }
        integrity = null
    }

    /** Validate the active token on launch (Twitch requires hourly validation for long sessions). */
    suspend fun validateActive(): Boolean {
        val token = accessToken() ?: return false
        return auth.validate(token) != null || refresh() != null
    }

    private suspend fun saveSecrets(userId: String, s: AccountSecrets) {
        secretsStore.updateData { it.copy(byUserId = it.byUserId + (userId to s)) }
    }

    private fun TokenResponse.expiresAt(): Long? = expiresIn?.let { System.currentTimeMillis() / 1000 + it }
}

private const val INTEGRITY_RETRY_MS = 10 * 60_000L
