package lol.sinist3r.yip.feature.settings

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import lol.sinist3r.yip.core.common.LogBuffer
import lol.sinist3r.yip.core.data.account.AccountRepository
import lol.sinist3r.yip.core.data.account.DeviceLoginState
import lol.sinist3r.yip.core.designsystem.component.Avatar
import lol.sinist3r.yip.core.network.twitch.TwitchAuthApi

@HiltViewModel
class LoginViewModel @Inject constructor(private val accounts: AccountRepository) : ViewModel() {
    private val _state = MutableStateFlow<DeviceLoginState>(DeviceLoginState.Starting)
    val state = _state.asStateFlow()

    fun startDevice() = viewModelScope.launch { accounts.deviceLogin().collect { _state.value = it } }
    fun browserUrl(): String = accounts.beginBrowserLogin()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(onDone: () -> Unit, onBack: () -> Unit, viewModel: LoginViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(Unit) { viewModel.startDevice() }
    LaunchedEffect(state) { if (state is DeviceLoginState.Success) onDone() }

    Scaffold(topBar = { TopAppBar(title = { Text("Sign in to Twitch") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (val s = state) {
                DeviceLoginState.Starting -> CircularProgressIndicator()
                is DeviceLoginState.WaitingForUser -> {
                    Text("Approve Yip on Twitch with this code", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer) {
                        Text(s.userCode, fontSize = 40.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, letterSpacing = 6.sp, modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            val uri = Uri.parse(s.verificationUri).buildUpon().appendQueryParameter("device-code", s.userCode).build()
                            CustomTabsIntent.Builder().build().launchUrl(context, uri)
                        }) { Text("Open twitch.tv/activate") }
                        OutlinedButton(onClick = { clipboard.setText(AnnotatedString(s.userCode)) }) { Icon(Icons.Outlined.ContentCopy, "Copy code") }
                    }
                    Text("You can also enter it from another device. Yip never sees your password.", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.height(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.padding(4.dp))
                        Text("Waiting for approval…", style = MaterialTheme.typography.bodySmall)
                    }
                }
                is DeviceLoginState.Failed -> {
                    Text(s.reason, textAlign = TextAlign.Center)
                    Button(onClick = viewModel::startDevice) { Text("Try again") }
                }
                is DeviceLoginState.Success -> Icon(Icons.Outlined.Check, null)
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            TextButton(onClick = { CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(viewModel.browserUrl())) }) {
                Text("Sign in with the browser instead")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsScreen(nav: SettingsNavigator, viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.accountsState.collectAsStateWithLifecycle()
    Scaffold(topBar = { TopAppBar(title = { Text("Accounts") }, navigationIcon = { IconButton(onClick = nav::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            items(state.accounts, key = { it.userId }) { a ->
                val active = a.userId == state.activeUserId
                ListItem(
                    headlineContent = { Text(a.displayName) },
                    supportingContent = { Text(if (active) "Active" + if (a.hasWebToken) " · twitch.tv connected" else "" else "Tap to switch") },
                    leadingContent = { Avatar(a.profileImageUrl, null, 40.dp) },
                    trailingContent = { IconButton(onClick = { viewModel.signOut(a.userId) }) { Icon(Icons.AutoMirrored.Outlined.Logout, "Sign out ${a.displayName}") } },
                    modifier = Modifier.clickable(enabled = !active) { viewModel.switchAccount(a.userId) },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(if (state.accounts.isEmpty()) "Sign in" else "Add another account") },
                    leadingContent = { Icon(Icons.Outlined.Add, null) },
                    modifier = Modifier.clickable(onClick = nav::openLogin),
                )
            }
            state.active?.let { a ->
                item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
                item {
                    ListItem(
                        headlineContent = { Text(if (a.hasWebToken) "Disconnect twitch.tv" else "Connect twitch.tv (experimental)") },
                        supportingContent = { Text("Enables channel points, one-tap follow and prediction voting through Twitch's private API. Signs in on twitch.tv inside Yip; the token is stored encrypted on this device.") },
                        leadingContent = { Icon(if (a.hasWebToken) Icons.Outlined.LinkOff else Icons.Outlined.Link, null) },
                        modifier = Modifier.clickable { if (a.hasWebToken) viewModel.disconnectWeb() else nav.openConnectWeb() },
                    )
                }
            }
        }
    }
}

@HiltViewModel
class ConnectWebViewModel @Inject constructor(private val accounts: AccountRepository, private val auth: TwitchAuthApi) : ViewModel() {
    val result = MutableStateFlow<String?>(null)
    @Volatile private var handled = false

    fun onToken(token: String, clientId: String?) {
        if (handled) return
        handled = true
        viewModelScope.launch {
            val v = auth.validate(token)
            val active = accounts.state.value.active
            result.value = when {
                v == null -> { handled = false; null }
                active != null && v.userId != active.userId -> "That twitch.tv login is ${v.login}, but Yip is signed in as ${active.login}. Log in with the same account."
                else -> { accounts.connectWebToken(token, clientId ?: v.clientId); "ok" }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectWebScreen(onDone: () -> Unit, onBack: () -> Unit, viewModel: ConnectWebViewModel = hiltViewModel()) {
    val result by viewModel.result.collectAsStateWithLifecycle()
    LaunchedEffect(result) { if (result == "ok") onDone() }
    DisposableEffect(Unit) {
        onDispose {
            // Don't leave a twitch.tv session lying around in the WebView.
            CookieManager.getInstance().removeAllCookies(null)
        }
    }
    Scaffold(topBar = { TopAppBar(title = { Text("Connect twitch.tv") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            result?.takeIf { it != "ok" }?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    CookieManager.getInstance().removeAllCookies(null)
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                                if (request.url.host == "gql.twitch.tv") {
                                    val auth = request.requestHeaders.entries.firstOrNull { it.key.equals("Authorization", true) }?.value
                                    val token = auth?.removePrefix("OAuth ")?.trim()
                                    if (!token.isNullOrBlank() && token != "undefined") {
                                        val clientId = request.requestHeaders.entries.firstOrNull { it.key.equals("Client-Id", true) }?.value
                                        viewModel.onToken(token, clientId)
                                    }
                                }
                                return null
                            }

                            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                                CookieManager.getInstance().getCookie("https://www.twitch.tv")?.split(';')
                                    ?.map { it.trim() }?.firstOrNull { it.startsWith("auth-token=") }
                                    ?.removePrefix("auth-token=")?.takeIf { it.isNotBlank() }
                                    ?.let { viewModel.onToken(it, null) }
                            }

                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                                request.url.host?.endsWith("twitch.tv") != true // keep the user on Twitch
                        }
                        loadUrl("https://www.twitch.tv/login")
                    }
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(versionName: String, nav: SettingsNavigator, viewModel: SettingsViewModel = hiltViewModel()) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    val uri = LocalUriHandler.current
    Scaffold(topBar = { TopAppBar(title = { Text("About Yip") }, navigationIcon = { IconButton(onClick = nav::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item { Text("Yip $versionName", style = MaterialTheme.typography.headlineSmall) }
            item { Text("An unofficial Twitch client for Android. Not affiliated with Twitch or Amazon.", style = MaterialTheme.typography.bodyMedium) }
            item { Spacer(Modifier.height(12.dp)) }
            item {
                ListItem(
                    headlineContent = { Text("Playback config") },
                    supportingContent = { Text(if (config.generatedAt > 0) "Signed config from ${java.util.Date(config.generatedAt * 1000)}" else "Built-in defaults") },
                )
            }
            config.message?.let { msg -> item { Text(msg, color = MaterialTheme.colorScheme.primary) } }
            item { ListItem(headlineContent = { Text("Licence") }, supportingContent = { Text("GNU GPL v3.0") }) }
            item {
                ListItem(
                    headlineContent = { Text("Thanks") },
                    supportingContent = { Text("7TV, BetterTTV, FrankerFaceZ, Chatterino and the recent-messages service for their public APIs. Xtra, Frosty and Streamlink for paving the way.") },
                )
            }
            item { ListItem(headlineContent = { Text("Open-source libraries") }, supportingContent = { Text("AndroidX, Jetpack Compose, Media3, Kotlin coroutines and serialization, Ktor, OkHttp, Coil, Hilt, Room, Bouncy Castle, Timber") }) }
            item { TextButton(onClick = { uri.openUri("https://yip.sinist3r.lol") }) { Text("yip.sinist3r.lol") } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(onBack: () -> Unit) {
    var lines by remember { mutableStateOf(LogBuffer.snapshot()) }
    val clipboard = LocalClipboardManager.current
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Logs") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {
                IconButton(onClick = { clipboard.setText(AnnotatedString(lines.joinToString("\n"))) }) { Icon(Icons.Outlined.ContentCopy, "Copy all") }
                TextButton(onClick = { LogBuffer.clear(); lines = emptyList() }) { Text("Clear") }
            },
        )
    }) { padding ->
        SelectionContainer(Modifier.padding(padding)) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), reverseLayout = true) {
                items(lines.reversed()) { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.fillMaxWidth()) }
            }
        }
    }
}
