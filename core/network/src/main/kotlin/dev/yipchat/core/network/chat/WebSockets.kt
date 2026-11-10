package dev.yipchat.core.network.chat

import dev.yipchat.core.network.USER_AGENT
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

sealed interface WsEvent {
    data class Open(val socket: WebSocket) : WsEvent
    data class Text(val text: String) : WsEvent
    data class Closed(val code: Int, val reason: String) : WsEvent
    data class Failure(val error: Throwable) : WsEvent
}

/** A cold flow of WebSocket events. Collecting opens the socket; cancelling closes it. */
fun OkHttpClient.webSocketFlow(url: String): Flow<WsEvent> = callbackFlow {
    val socket = newWebSocket(
        Request.Builder().url(url).header("User-Agent", USER_AGENT).build(),
        object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { trySend(WsEvent.Open(webSocket)) }
            override fun onMessage(webSocket: WebSocket, text: String) { trySend(WsEvent.Text(text)) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                trySend(WsEvent.Closed(code, reason))
                close()
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                trySend(WsEvent.Failure(t))
                close()
            }
        },
    )
    awaitClose { socket.close(1000, null) }
}
