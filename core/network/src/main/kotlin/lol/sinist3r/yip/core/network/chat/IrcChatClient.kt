package lol.sinist3r.yip.core.network.chat

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import okhttp3.OkHttpClient
import okhttp3.WebSocket

/**
 * Anonymous read-only IRC, used when signed out (EventSub needs a user token).
 * Reconnects with backoff and re-requests capabilities each time.
 */
@Singleton
class IrcChatClient @Inject constructor(private val okHttp: OkHttpClient) {

    fun join(channelLogin: String): Flow<RawChat> = channelFlow {
        var attempt = 0
        while (true) {
            var socket: WebSocket? = null
            okHttp.webSocketFlow("wss://irc-ws.chat.twitch.tv:443").collect { ev ->
                when (ev) {
                    is WsEvent.Open -> {
                        socket = ev.socket
                        attempt = 0
                        ev.socket.send("CAP REQ :twitch.tv/tags twitch.tv/commands")
                        ev.socket.send("PASS SCHMOOPIIE")
                        ev.socket.send("NICK justinfan${Random.nextInt(10_000, 99_999)}")
                        ev.socket.send("JOIN #${channelLogin.lowercase()}")
                        send(RawChat.Status(connected = true, readOnly = true))
                    }
                    is WsEvent.Text -> ev.text.split("\r\n").forEach { raw ->
                        val line = IrcParser.parseLine(raw) ?: return@forEach
                        when (line.command) {
                            "PING" -> socket?.send("PONG :${line.trailing}")
                            "RECONNECT" -> socket?.close(1000, "reconnect")
                            else -> IrcParser.toRawChat(line)?.let { send(it) }
                        }
                    }
                    is WsEvent.Closed, is WsEvent.Failure -> send(RawChat.Status(connected = false, readOnly = true))
                }
            }
            attempt++
            delay((attempt.coerceAtMost(6) * 2).seconds)
        }
    }
}
