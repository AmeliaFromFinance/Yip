package dev.yipchat.core.data.chat

/** [userArg]: the first argument is a username, so suggest chatters for it. */
data class ChatCommand(val name: String, val args: String, val description: String, val role: Role = Role.ANYONE, val userArg: Boolean = false) {
    enum class Role { ANYONE, MOD, BROADCASTER }
}

/** Twitch chat commands Yip runs through Twitch's API (typed commands were retired by Twitch). */
object ChatCommands {
    val all = listOf(
        ChatCommand("/me", "<message>", "Send in italics, as an action"),
        ChatCommand("/w", "<user> <message>", "Whisper someone", userArg = true),
        ChatCommand("/color", "<colour or #hex>", "Change your name colour"),
        ChatCommand("/block", "<user>", "Block someone", userArg = true),
        ChatCommand("/unblock", "<user>", "Unblock someone", userArg = true),
        ChatCommand("/help", "", "List commands"),
        ChatCommand("/timeout", "<user> [seconds] [reason]", "Time someone out (default 10 min)", ChatCommand.Role.MOD, userArg = true),
        ChatCommand("/ban", "<user> [reason]", "Ban someone", ChatCommand.Role.MOD, userArg = true),
        ChatCommand("/unban", "<user>", "Lift a ban or timeout", ChatCommand.Role.MOD, userArg = true),
        ChatCommand("/clear", "", "Clear chat for everyone", ChatCommand.Role.MOD),
        ChatCommand("/slow", "[seconds]", "Slow mode (default 30 s)", ChatCommand.Role.MOD),
        ChatCommand("/slowoff", "", "Turn off slow mode", ChatCommand.Role.MOD),
        ChatCommand("/followers", "[minutes]", "Followers-only chat", ChatCommand.Role.MOD),
        ChatCommand("/followersoff", "", "Turn off followers-only", ChatCommand.Role.MOD),
        ChatCommand("/emoteonly", "", "Emote-only chat", ChatCommand.Role.MOD),
        ChatCommand("/emoteonlyoff", "", "Turn off emote-only", ChatCommand.Role.MOD),
        ChatCommand("/subscribers", "", "Subscriber-only chat", ChatCommand.Role.MOD),
        ChatCommand("/subscribersoff", "", "Turn off subscriber-only", ChatCommand.Role.MOD),
        ChatCommand("/uniquechat", "", "Block repeated messages", ChatCommand.Role.MOD),
        ChatCommand("/uniquechatoff", "", "Allow repeated messages", ChatCommand.Role.MOD),
        ChatCommand("/announce", "<message>", "Highlighted announcement", ChatCommand.Role.MOD),
        ChatCommand("/shoutout", "<user>", "Shout out another streamer", ChatCommand.Role.MOD, userArg = true),
        ChatCommand("/vip", "<user>", "Make someone a VIP", ChatCommand.Role.BROADCASTER, userArg = true),
        ChatCommand("/unvip", "<user>", "Remove a VIP", ChatCommand.Role.BROADCASTER, userArg = true),
        ChatCommand("/mod", "<user>", "Make someone a moderator", ChatCommand.Role.BROADCASTER, userArg = true),
        ChatCommand("/unmod", "<user>", "Remove a moderator", ChatCommand.Role.BROADCASTER, userArg = true),
        ChatCommand("/raid", "<channel>", "Raid another channel", ChatCommand.Role.BROADCASTER, userArg = true),
        ChatCommand("/unraid", "", "Cancel a raid", ChatCommand.Role.BROADCASTER),
        ChatCommand("/marker", "[description]", "Add a stream marker", ChatCommand.Role.BROADCASTER),
        ChatCommand("/commercial", "[30-180]", "Run an ad", ChatCommand.Role.BROADCASTER),
    )

    fun available(isMod: Boolean, isBroadcaster: Boolean) = all.filter {
        when (it.role) {
            ChatCommand.Role.ANYONE -> true
            ChatCommand.Role.MOD -> isMod || isBroadcaster
            ChatCommand.Role.BROADCASTER -> isBroadcaster
        }
    }

    fun find(name: String) = all.firstOrNull { it.name.equals(name, true) }
}
