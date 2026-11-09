package lol.sinist3r.yip.feature.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.ui.graphics.vector.ImageVector
import lol.sinist3r.yip.core.model.AccentColor
import lol.sinist3r.yip.core.model.AdMethod
import lol.sinist3r.yip.core.model.AnimateEmotes
import lol.sinist3r.yip.core.model.AppSettings
import lol.sinist3r.yip.core.model.BufferProfile
import lol.sinist3r.yip.core.model.CardLayout
import lol.sinist3r.yip.core.model.DeletedMessages
import lol.sinist3r.yip.core.model.LandscapeChat
import lol.sinist3r.yip.core.model.LinkOpening
import lol.sinist3r.yip.core.model.PaintMode
import lol.sinist3r.yip.core.model.PlayerTypeOption
import lol.sinist3r.yip.core.model.ProviderSlot
import lol.sinist3r.yip.core.model.QualityPreference
import lol.sinist3r.yip.core.model.StartScreen
import lol.sinist3r.yip.core.model.ThemeMode
import lol.sinist3r.yip.core.model.TimestampStyle
import lol.sinist3r.yip.core.model.UsernameStyle
import lol.sinist3r.yip.core.model.VideoCodec

/** One row in a settings page. Pure description; the UI renders it. */
sealed interface Row {
    val title: String
    val summary: String?

    data class Header(override val title: String, override val summary: String? = null) : Row
    data class Note(override val title: String, override val summary: String? = null, val warning: Boolean = false) : Row
    data class Toggle(
        override val title: String,
        override val summary: String? = null,
        val get: (AppSettings) -> Boolean,
        val set: (AppSettings, Boolean) -> AppSettings,
        val enabled: (AppSettings) -> Boolean = { true },
        val confirm: String? = null,
    ) : Row
    data class Choice(
        override val title: String,
        override val summary: String? = null,
        val options: List<Pair<String, String>>,
        val get: (AppSettings) -> String,
        val set: (AppSettings, String) -> AppSettings,
        val enabled: (AppSettings) -> Boolean = { true },
    ) : Row
    data class Slider(
        override val title: String,
        override val summary: String? = null,
        val range: ClosedFloatingPointRange<Float>,
        val steps: Int,
        val format: (Float) -> String,
        val get: (AppSettings) -> Float,
        val set: (AppSettings, Float) -> AppSettings,
        val enabled: (AppSettings) -> Boolean = { true },
    ) : Row
    data class Words(
        override val title: String,
        override val summary: String? = null,
        val placeholder: String,
        val get: (AppSettings) -> List<String>,
        val set: (AppSettings, List<String>) -> AppSettings,
        val lowercase: Boolean = true,
        val validate: (String) -> String? = { null },
    ) : Row
    data class MultiChoice(
        override val title: String,
        override val summary: String? = null,
        val options: List<Pair<String, String>>,
        val get: (AppSettings) -> List<String>,
        val set: (AppSettings, List<String>) -> AppSettings,
        val ordered: Boolean = false,
    ) : Row
    data class Action(override val title: String, override val summary: String? = null, val id: String, val destructive: Boolean = false) : Row
}

data class Page(val key: String, val title: String, val summary: String, val icon: ImageVector, val rows: List<Row>)

private inline fun <reified E : Enum<E>> opts(vararg labels: Pair<E, String>) = labels.map { it.first.name to it.second }

object SettingsSpec {

    val accountPage = Page("account", "Accounts", "Sign in, switch accounts, twitch.tv connection", Icons.Outlined.AccountCircle, emptyList())

    val general = Page(
        "general", "General", "Start screen, links, languages, updates", Icons.Outlined.Tune,
        listOf(
            Row.Choice("Start screen", options = opts(StartScreen.FOLLOWING to "Following", StartScreen.BROWSE to "Browse", StartScreen.SEARCH to "Search"),
                get = { it.general.startScreen.name }, set = { s, v -> s.copy(general = s.general.copy(startScreen = StartScreen.valueOf(v))) }),
            Row.Choice("Open links", "Links tapped in chat and descriptions", opts(LinkOpening.IN_APP to "In-app browser tab", LinkOpening.BROWSER to "Default browser", LinkOpening.ASK to "Ask every time"),
                get = { it.general.openLinks.name }, set = { s, v -> s.copy(general = s.general.copy(openLinks = LinkOpening.valueOf(v))) }),
            Row.Words("Stream languages", "Only show streams in these languages when browsing. Empty shows all.", "Language code, e.g. en, de, ja",
                get = { it.general.streamLanguages }, set = { s, v -> s.copy(general = s.general.copy(streamLanguages = v)) },
                validate = { if (it.length in 2..3 && it.all(Char::isLetter)) null else "Use a 2 or 3 letter language code" }),
            Row.Toggle("Show offline channels", "List followed channels that aren't live under Following",
                get = { it.general.showOfflineFollowed }, set = { s, v -> s.copy(general = s.general.copy(showOfflineFollowed = v)) }),
            Row.Toggle("Hide mature streams", "Streams marked for mature audiences (signed in only)",
                get = { it.general.hideMatureStreams }, set = { s, v -> s.copy(general = s.general.copy(hideMatureStreams = v)) }),
            Row.Toggle("Continue watching", "Show recently watched streams and VODs on Following",
                get = { it.general.continueWatching }, set = { s, v -> s.copy(general = s.general.copy(continueWatching = v)) }),
            Row.Toggle("Confirm before exit", get = { it.general.confirmBeforeExit }, set = { s, v -> s.copy(general = s.general.copy(confirmBeforeExit = v)) }),
            Row.Header("Updates"),
            Row.Toggle("Check for updates", "Looks for a new release on GitHub every few hours",
                get = { it.updates.autoCheck }, set = { s, v -> s.copy(updates = s.updates.copy(autoCheck = v)) }),
            Row.Toggle("Include pre-releases", "Test builds that may be less stable",
                get = { it.updates.prereleases }, set = { s, v -> s.copy(updates = s.updates.copy(prereleases = v)) }),
            Row.Action("Check now", null, "check_updates"),
        ),
    )

    val appearance = Page(
        "appearance", "Appearance", "Theme, colours, layout", Icons.Outlined.Palette,
        listOf(
            Row.Choice("Theme", options = opts(ThemeMode.SYSTEM to "Follow system", ThemeMode.LIGHT to "Light", ThemeMode.DARK to "Dark", ThemeMode.BLACK to "Black (OLED)"),
                get = { it.appearance.theme.name }, set = { s, v -> s.copy(appearance = s.appearance.copy(theme = ThemeMode.valueOf(v))) }),
            Row.Choice("Accent colour", options = opts(
                AccentColor.FOX to "Fox (amber)", AccentColor.CYAN to "Cyan", AccentColor.ROSE to "Rose", AccentColor.VIOLET to "Violet",
                AccentColor.MINT to "Mint", AccentColor.SKY to "Sky", AccentColor.CRIMSON to "Crimson", AccentColor.DYNAMIC to "Wallpaper (Material You)",
            ), get = { it.appearance.accent.name }, set = { s, v -> s.copy(appearance = s.appearance.copy(accent = AccentColor.valueOf(v), customAccentArgb = null)) }),
            Row.Choice("Stream cards", options = opts(CardLayout.GRID to "Grid with thumbnails", CardLayout.LIST to "List with thumbnails", CardLayout.COMPACT to "Compact list"),
                get = { it.appearance.cardLayout.name }, set = { s, v -> s.copy(appearance = s.appearance.copy(cardLayout = CardLayout.valueOf(v))) }),
            Row.Slider("Grid columns", "0 picks automatically for your screen", 0f..5f, 4, { if (it < 0.5f) "Auto" else it.toInt().toString() },
                get = { it.appearance.gridColumns.toFloat() }, set = { s, v -> s.copy(appearance = s.appearance.copy(gridColumns = v.toInt())) },
                enabled = { it.appearance.cardLayout == CardLayout.GRID }),
            Row.Slider("Corner roundness", null, 0f..24f, 5, { "${it.toInt()} dp" },
                get = { it.appearance.roundedCorners.toFloat() }, set = { s, v -> s.copy(appearance = s.appearance.copy(roundedCorners = v.toInt())) }),
            Row.Header("On stream cards"),
            Row.Toggle("Viewer count", get = { it.appearance.showViewerCount }, set = { s, v -> s.copy(appearance = s.appearance.copy(showViewerCount = v)) }),
            Row.Toggle("Uptime", get = { it.appearance.showUptime }, set = { s, v -> s.copy(appearance = s.appearance.copy(showUptime = v)) }),
            Row.Toggle("Category", get = { it.appearance.showCategory }, set = { s, v -> s.copy(appearance = s.appearance.copy(showCategory = v)) }),
            Row.Toggle("Tags", get = { it.appearance.showTags }, set = { s, v -> s.copy(appearance = s.appearance.copy(showTags = v)) }),
            Row.Header("Accessibility"),
            Row.Toggle("Reduce motion", "Fewer animations, including animated emotes", get = { it.appearance.reduceMotion }, set = { s, v -> s.copy(appearance = s.appearance.copy(reduceMotion = v)) }),
            Row.Toggle("Labels on bottom bar", get = { it.appearance.bottomBarLabels }, set = { s, v -> s.copy(appearance = s.appearance.copy(bottomBarLabels = v)) }),
        ),
    )

    private val qualityOptions = opts(
        QualityPreference.AUTO to "Auto", QualityPreference.SOURCE to "Source (best)", QualityPreference.P1440 to "1440p", QualityPreference.P1080 to "1080p",
        QualityPreference.P720 to "720p", QualityPreference.P480 to "480p", QualityPreference.P360 to "360p", QualityPreference.P160 to "160p", QualityPreference.AUDIO_ONLY to "Audio only",
    )

    val player = Page(
        "player", "Player", "Quality, latency, background, gestures", Icons.Outlined.PlayCircle,
        listOf(
            Row.Header("Quality"),
            Row.Choice("On Wi-Fi", options = qualityOptions, get = { it.player.qualityWifi.name }, set = { s, v -> s.copy(player = s.player.copy(qualityWifi = QualityPreference.valueOf(v))) }),
            Row.Choice("On mobile data", options = qualityOptions, get = { it.player.qualityMobile.name }, set = { s, v -> s.copy(player = s.player.copy(qualityMobile = QualityPreference.valueOf(v))) }),
            Row.Toggle("Remember quality per channel", get = { it.player.rememberQualityPerChannel }, set = { s, v -> s.copy(player = s.player.copy(rememberQualityPerChannel = v)) }),
            Row.MultiChoice("Allowed codecs", "H.265 and AV1 save data but need hardware support", listOf("H264" to "H.264", "H265" to "H.265 / HEVC", "AV1" to "AV1"),
                get = { it.player.codecs.map(VideoCodec::name) }, set = { s, v -> s.copy(player = s.player.copy(codecs = v.map(VideoCodec::valueOf).ifEmpty { listOf(VideoCodec.H264) })) }),
            Row.Header("Latency"),
            Row.Choice("Buffer", "Lower latency stalls more on weak connections", opts(BufferProfile.LOW_LATENCY to "Low latency", BufferProfile.BALANCED to "Balanced", BufferProfile.STABLE to "Stable"),
                get = { it.player.bufferProfile.name }, set = { s, v -> s.copy(player = s.player.copy(bufferProfile = BufferProfile.valueOf(v))) }),
            Row.Toggle("Show latency", "Delay behind the streamer, shown on the player", get = { it.player.showLatency }, set = { s, v -> s.copy(player = s.player.copy(showLatency = v)) }),
            Row.Toggle("Stats overlay", "Resolution, frame rate and bitrate", get = { it.player.showStatsOverlay }, set = { s, v -> s.copy(player = s.player.copy(showStatsOverlay = v)) }),
            Row.Header("Background"),
            Row.Toggle("Keep playing in the background", get = { it.player.backgroundAudio }, set = { s, v -> s.copy(player = s.player.copy(backgroundAudio = v)) }),
            Row.Toggle("Picture-in-picture when leaving", get = { it.player.autoPip }, set = { s, v -> s.copy(player = s.player.copy(autoPip = v)) }),
            Row.Toggle("Pause when headphones disconnect", get = { it.player.pauseOnHeadphonesUnplugged }, set = { s, v -> s.copy(player = s.player.copy(pauseOnHeadphonesUnplugged = v)) }),
            Row.Toggle("Pause during calls and other audio", get = { it.player.pauseOnCall }, set = { s, v -> s.copy(player = s.player.copy(pauseOnCall = v)) }),
            Row.Toggle("Keep screen on while watching", get = { it.player.keepScreenOn }, set = { s, v -> s.copy(player = s.player.copy(keepScreenOn = v)) }),
            Row.Toggle("Start muted", get = { it.player.startMuted }, set = { s, v -> s.copy(player = s.player.copy(startMuted = v)) }),
            Row.Slider("Default sleep timer", "Pause automatically after this long. 0 is off.", 0f..180f, 11, { if (it < 1) "Off" else "${it.toInt()} min" },
                get = { it.player.sleepTimerMinutes.toFloat() }, set = { s, v -> s.copy(player = s.player.copy(sleepTimerMinutes = v.toInt())) }),
            Row.Header("Gestures"),
            Row.Toggle("Swipe left side for brightness", get = { it.player.swipeBrightness }, set = { s, v -> s.copy(player = s.player.copy(swipeBrightness = v)) }),
            Row.Toggle("Swipe right side for volume", get = { it.player.swipeVolume }, set = { s, v -> s.copy(player = s.player.copy(swipeVolume = v)) }),
            Row.Toggle("Pinch to fill the screen", get = { it.player.pinchToZoom }, set = { s, v -> s.copy(player = s.player.copy(pinchToZoom = v)) }),
            Row.Slider("Double-tap seek (VODs)", null, 5f..30f, 4, { "${it.toInt()} s" },
                get = { it.player.doubleTapSeekSeconds.toFloat() }, set = { s, v -> s.copy(player = s.player.copy(doubleTapSeekSeconds = v.toInt())) }),
            Row.Toggle("Resume VODs where you left off", get = { it.player.resumeVods }, set = { s, v -> s.copy(player = s.player.copy(resumeVods = v)) }),
            Row.Header("Landscape"),
            Row.Choice("Chat in landscape", options = opts(LandscapeChat.OVERLAY to "Overlay on video", LandscapeChat.SIDE to "Beside video", LandscapeChat.HIDDEN to "Hidden"),
                get = { it.player.landscapeChat.name }, set = { s, v -> s.copy(player = s.player.copy(landscapeChat = LandscapeChat.valueOf(v))) }),
            Row.Slider("Overlay opacity", null, 0f..0.9f, 8, { "${(it * 100).toInt()}%" },
                get = { it.player.overlayChatOpacity }, set = { s, v -> s.copy(player = s.player.copy(overlayChatOpacity = v)) }, enabled = { it.player.landscapeChat == LandscapeChat.OVERLAY }),
            Row.Slider("Overlay width", null, 0.2f..0.6f, 7, { "${(it * 100).toInt()}%" },
                get = { it.player.overlayChatWidthFraction }, set = { s, v -> s.copy(player = s.player.copy(overlayChatWidthFraction = v)) }, enabled = { it.player.landscapeChat == LandscapeChat.OVERLAY }),
            Row.Header("Advanced"),
            Row.Choice("Player type", "Which Twitch player Yip identifies as. Change only if streams stop loading.",
                PlayerTypeOption.entries.map { it.name to it.wire }, get = { it.player.playerType.name }, set = { s, v -> s.copy(player = s.player.copy(playerType = PlayerTypeOption.valueOf(v))) }),
        ),
    )

    val chat = Page(
        "chat", "Chat", "Text, timestamps, messages, composer", Icons.Outlined.Chat,
        listOf(
            Row.Header("Text"),
            Row.Slider("Font size", null, 10f..22f, 23, { "%.1f".format(it) }, get = { it.chat.fontSizeSp }, set = { s, v -> s.copy(chat = s.chat.copy(fontSizeSp = v)) }),
            Row.Slider("Line spacing", null, 1f..2f, 9, { "%.1fx".format(it) }, get = { it.chat.lineSpacing }, set = { s, v -> s.copy(chat = s.chat.copy(lineSpacing = v)) }),
            Row.Choice("Timestamps", options = opts(TimestampStyle.OFF to "Off", TimestampStyle.H24 to "24-hour", TimestampStyle.H12 to "12-hour", TimestampStyle.RELATIVE to "Relative (2m)"),
                get = { it.chat.timestamps.name }, set = { s, v -> s.copy(chat = s.chat.copy(timestamps = TimestampStyle.valueOf(v))) }),
            Row.Toggle("Seconds in timestamps", get = { it.chat.timestampSeconds }, set = { s, v -> s.copy(chat = s.chat.copy(timestampSeconds = v)) }, enabled = { it.chat.timestamps == TimestampStyle.H24 || it.chat.timestamps == TimestampStyle.H12 }),
            Row.Choice("Usernames", options = opts(UsernameStyle.DISPLAY to "Display name", UsernameStyle.LOGIN to "Login name", UsernameStyle.BOTH to "Both when different"),
                get = { it.chat.usernameStyle.name }, set = { s, v -> s.copy(chat = s.chat.copy(usernameStyle = UsernameStyle.valueOf(v))) }),
            Row.Toggle("Name colours", get = { it.chat.showNameColors }, set = { s, v -> s.copy(chat = s.chat.copy(showNameColors = v)) }),
            Row.Toggle("Make dark names readable", "Adjusts colours that are hard to see on your theme", get = { it.chat.readableColors }, set = { s, v -> s.copy(chat = s.chat.copy(readableColors = v)) }),
            Row.Toggle("Badges", get = { it.chat.showBadges }, set = { s, v -> s.copy(chat = s.chat.copy(showBadges = v)) }),
            Row.Toggle("Pronouns", "From pronouns.alejo.io, shown before names", get = { it.chat.showPronouns }, set = { s, v -> s.copy(chat = s.chat.copy(showPronouns = v)) }),
            Row.Toggle("Alternate row shading", get = { it.chat.alternateRows }, set = { s, v -> s.copy(chat = s.chat.copy(alternateRows = v)) }),
            Row.Toggle("Separator lines", get = { it.chat.separators }, set = { s, v -> s.copy(chat = s.chat.copy(separators = v)) }),
            Row.Header("Messages"),
            Row.Slider("Messages kept", "More uses more memory", 100f..2000f, 18, { it.toInt().toString() }, get = { it.chat.messageLimit.toFloat() }, set = { s, v -> s.copy(chat = s.chat.copy(messageLimit = v.toInt())) }),
            Row.Toggle("Load recent chat on open", "Shows the last messages instantly using the community recent-messages service", get = { it.chat.loadRecentHistory }, set = { s, v -> s.copy(chat = s.chat.copy(loadRecentHistory = v)) }),
            Row.Choice("Deleted messages", options = opts(DeletedMessages.STRIKE to "Strike through", DeletedMessages.PLACEHOLDER to "Show <deleted>", DeletedMessages.HIDE to "Hide"),
                get = { it.chat.deletedMessages.name }, set = { s, v -> s.copy(chat = s.chat.copy(deletedMessages = DeletedMessages.valueOf(v))) }),
            Row.Toggle("Show timeouts and bans", get = { it.chat.showModActions }, set = { s, v -> s.copy(chat = s.chat.copy(showModActions = v)) }),
            Row.Toggle("Replies", "Show what a reply is answering", get = { it.chat.showReplies }, set = { s, v -> s.copy(chat = s.chat.copy(showReplies = v)) }),
            Row.Toggle("First-message tag", get = { it.chat.showFirstMessageTag }, set = { s, v -> s.copy(chat = s.chat.copy(showFirstMessageTag = v)) }),
            Row.Toggle("Subs and gifts", get = { it.chat.showSubNotices }, set = { s, v -> s.copy(chat = s.chat.copy(showSubNotices = v)) }),
            Row.Toggle("Raids", get = { it.chat.showRaids }, set = { s, v -> s.copy(chat = s.chat.copy(showRaids = v)) }),
            Row.Toggle("Channel point redemptions", get = { it.chat.showRedemptions }, set = { s, v -> s.copy(chat = s.chat.copy(showRedemptions = v)) }),
            Row.Toggle("Joined / disconnected lines", get = { it.chat.showJoinMessage }, set = { s, v -> s.copy(chat = s.chat.copy(showJoinMessage = v)) }),
            Row.Toggle("Clickable links", get = { it.chat.clickableLinks }, set = { s, v -> s.copy(chat = s.chat.copy(clickableLinks = v)) }),
            Row.Toggle("Tap a name to mention", "Instead of opening their user card", get = { it.chat.tapNameToMention }, set = { s, v -> s.copy(chat = s.chat.copy(tapNameToMention = v)) }),
            Row.Header("Mentions"),
            Row.Toggle("Highlight mentions of you", get = { it.chat.highlightMentions }, set = { s, v -> s.copy(chat = s.chat.copy(highlightMentions = v)) }),
            Row.Toggle("Vibrate on mention", get = { it.chat.mentionVibrate }, set = { s, v -> s.copy(chat = s.chat.copy(mentionVibrate = v)) }),
            Row.Header("Composer"),
            Row.Toggle("Send with Enter", get = { it.chat.sendOnEnter }, set = { s, v -> s.copy(chat = s.chat.copy(sendOnEnter = v)) }),
            Row.Toggle("Emote suggestions while typing", get = { it.chat.autocompleteEmotes }, set = { s, v -> s.copy(chat = s.chat.copy(autocompleteEmotes = v)) }),
            Row.Toggle("@username suggestions", get = { it.chat.autocompleteUsers }, set = { s, v -> s.copy(chat = s.chat.copy(autocompleteUsers = v)) }),
            Row.Toggle("Tab cycles through matches", "With a hardware keyboard, like Chatterino", get = { it.chat.tabCompletion }, set = { s, v -> s.copy(chat = s.chat.copy(tabCompletion = v)) }),
            Row.Toggle("Recent emotes row", get = { it.chat.showRecentEmotes }, set = { s, v -> s.copy(chat = s.chat.copy(showRecentEmotes = v)) }),
            Row.Toggle("Keep text after sending", "Handy for repeating a message", get = { it.chat.keepComposerOnSend }, set = { s, v -> s.copy(chat = s.chat.copy(keepComposerOnSend = v)) }),
        ),
    )

    val emotes = Page(
        "emotes", "Emotes and cosmetics", "7TV, BTTV, FFZ, paints and badges", Icons.Outlined.EmojiEmotions,
        listOf(
            Row.Choice("Animate emotes", options = opts(AnimateEmotes.ALWAYS to "Always", AnimateEmotes.WIFI_ONLY to "On Wi-Fi only", AnimateEmotes.NEVER to "Never"),
                get = { it.chat.animateEmotes.name }, set = { s, v -> s.copy(chat = s.chat.copy(animateEmotes = AnimateEmotes.valueOf(v))) }),
            Row.Slider("Emote size", null, 0.6f..2f, 13, { "%.1fx".format(it) }, get = { it.chat.emoteScale }, set = { s, v -> s.copy(chat = s.chat.copy(emoteScale = v)) }),
            Row.Toggle("Zero-width emotes", "Overlay emotes like SoSnowy on the emote before them", get = { it.thirdParty.zeroWidthEmotes }, set = { s, v -> s.copy(thirdParty = s.thirdParty.copy(zeroWidthEmotes = v)) }),
            Row.MultiChoice("Provider priority", "When two providers use the same emote name, the first wins", ProviderSlot.entries.map { it.name to it.label() },
                get = { it.thirdParty.priority.map(ProviderSlot::name) }, set = { s, v -> s.copy(thirdParty = s.thirdParty.copy(priority = v.map(ProviderSlot::valueOf))) }, ordered = true),
            Row.Header("7TV"),
            Row.Toggle("Emotes", get = { it.thirdParty.seventvEmotes }, set = { s, v -> s.copy(thirdParty = s.thirdParty.copy(seventvEmotes = v)) }),
            Row.Choice("Name paints", options = opts(PaintMode.ANIMATED to "Gradient and glow", PaintMode.STATIC to "Gradient only", PaintMode.OFF to "Off"),
                get = { it.thirdParty.seventvPaints.name }, set = { s, v -> s.copy(thirdParty = s.thirdParty.copy(seventvPaints = PaintMode.valueOf(v))) }),
            Row.Toggle("Paint shadows", get = { it.thirdParty.seventvPaintShadows }, set = { s, v -> s.copy(thirdParty = s.thirdParty.copy(seventvPaintShadows = v)) }, enabled = { it.thirdParty.seventvPaints == PaintMode.ANIMATED }),
            Row.Toggle("Badges", get = { it.thirdParty.seventvBadges }, set = { s, v -> s.copy(thirdParty = s.thirdParty.copy(seventvBadges = v)) }),
            Row.Toggle("Personal emotes", "Emotes 7TV subscribers can use in any channel", get = { it.thirdParty.seventvPersonalEmotes }, set = { s, v -> s.copy(thirdParty = s.thirdParty.copy(seventvPersonalEmotes = v)) }),
            Row.Toggle("Live emote updates", "Add, remove and rename emotes without reopening chat", get = { it.thirdParty.seventvLiveUpdates }, set = { s, v -> s.copy(thirdParty = s.thirdParty.copy(seventvLiveUpdates = v)) }),
            Row.Header("BetterTTV"),
            Row.Toggle("Emotes", get = { it.thirdParty.bttvEmotes }, set = { s, v -> s.copy(thirdParty = s.thirdParty.copy(bttvEmotes = v)) }),
            Row.Toggle("Badges", get = { it.thirdParty.bttvBadges }, set = { s, v -> s.copy(thirdParty = s.thirdParty.copy(bttvBadges = v)) }),
            Row.Header("FrankerFaceZ"),
            Row.Toggle("Emotes", get = { it.thirdParty.ffzEmotes }, set = { s, v -> s.copy(thirdParty = s.thirdParty.copy(ffzEmotes = v)) }),
            Row.Toggle("Badges", get = { it.thirdParty.ffzBadges }, set = { s, v -> s.copy(thirdParty = s.thirdParty.copy(ffzBadges = v)) }),
            Row.Toggle("Custom mod and VIP badges", get = { it.thirdParty.ffzModBadges }, set = { s, v -> s.copy(thirdParty = s.thirdParty.copy(ffzModBadges = v)) }),
            Row.Toggle("FFZ:AP badges", "FFZ Add-On Pack supporters", get = { it.thirdParty.ffzApBadges }, set = { s, v -> s.copy(thirdParty = s.thirdParty.copy(ffzApBadges = v)) }),
            Row.Header("Chat clients"),
            Row.Toggle("Chatterino", get = { it.thirdParty.chatterinoBadges }, set = { s, v -> s.copy(thirdParty = s.thirdParty.copy(chatterinoBadges = v)) }),
            Row.Toggle("Chatterino Homies", "Developer, supporter and founder badges", get = { it.thirdParty.homiesBadges }, set = { s, v -> s.copy(thirdParty = s.thirdParty.copy(homiesBadges = v)) }),
            Row.Toggle("Chatterino Homies custom badges", "Badges users upload themselves", get = { it.thirdParty.homiesCustomBadges }, set = { s, v -> s.copy(thirdParty = s.thirdParty.copy(homiesCustomBadges = v)) }),
            Row.Toggle("Moltorino", "Supporter, donor and contributor badges", get = { it.thirdParty.moltorinoBadges }, set = { s, v -> s.copy(thirdParty = s.thirdParty.copy(moltorinoBadges = v)) }),
        ),
    )

    val filters = Page(
        "filters", "Filters and highlights", "Hide bots, blocked words, keywords", Icons.Outlined.FilterAlt,
        listOf(
            Row.Header("Hide"),
            Row.Toggle("Hide bots", get = { it.filters.hideBots }, set = { s, v -> s.copy(filters = s.filters.copy(hideBots = v)) }),
            Row.Words("Bot names", null, "Login name", get = { it.filters.botNames }, set = { s, v -> s.copy(filters = s.filters.copy(botNames = v)) }),
            Row.Toggle("Hide commands", "Messages starting with a command prefix", get = { it.filters.hideCommands }, set = { s, v -> s.copy(filters = s.filters.copy(hideCommands = v)) }),
            Row.Words("Command prefixes", null, "e.g. !", get = { it.filters.commandPrefixes }, set = { s, v -> s.copy(filters = s.filters.copy(commandPrefixes = v)) }, lowercase = false),
            Row.Words("Hidden users", "Never show messages from these people", "Login name", get = { it.filters.hiddenUsers }, set = { s, v -> s.copy(filters = s.filters.copy(hiddenUsers = v)) }),
            Row.Words("Blocked words", null, "Word or phrase", get = { it.filters.blockedTerms }, set = { s, v -> s.copy(filters = s.filters.copy(blockedTerms = v)) }, lowercase = false),
            Row.Toggle("Blocked words are regular expressions", get = { it.filters.blockedTermsAreRegex }, set = { s, v -> s.copy(filters = s.filters.copy(blockedTermsAreRegex = v)) }),
            Row.Toggle("Hide emote-only messages", get = { it.filters.hideEmoteOnly }, set = { s, v -> s.copy(filters = s.filters.copy(hideEmoteOnly = v)) }),
            Row.Toggle("Hide first-time chatters", get = { it.filters.hideFirstTimeChatters }, set = { s, v -> s.copy(filters = s.filters.copy(hideFirstTimeChatters = v)) }),
            Row.Header("Highlight"),
            Row.Words("Keywords", "Whole words, any case. Wrap in slashes for a pattern, e.g. /^!so\\b/", "Word, phrase or /pattern/", get = { it.filters.highlightKeywords }, set = { s, v -> s.copy(filters = s.filters.copy(highlightKeywords = v)) }, lowercase = false,
                validate = lol.sinist3r.yip.core.data.chat.KeywordMatcher::problem),
            Row.Words("Users", "Highlight messages from these people", "Login name", get = { it.filters.highlightUsers }, set = { s, v -> s.copy(filters = s.filters.copy(highlightUsers = v)) }),
        ),
    )

    val notifications = Page(
        "notifications", "Notifications", "Go-live alerts and chat pings", Icons.Outlined.Notifications,
        listOf(
            Row.Toggle("Live notifications", "Checks in the background. Needs you to be signed in.", get = { it.notifications.enabled }, set = { s, v -> s.copy(notifications = s.notifications.copy(enabled = v)) }),
            Row.Slider("Check every", "Android won't check more often than every 15 minutes", 15f..120f, 6, { "${it.toInt()} min" },
                get = { it.notifications.intervalMinutes.toFloat() }, set = { s, v -> s.copy(notifications = s.notifications.copy(intervalMinutes = v.toInt())) }, enabled = { it.notifications.enabled }),
            Row.Toggle("Only favourites", get = { it.notifications.onlyFavourites }, set = { s, v -> s.copy(notifications = s.notifications.copy(onlyFavourites = v)) }, enabled = { it.notifications.enabled }),
            Row.Words("Favourites", null, "Login name", get = { it.notifications.favourites }, set = { s, v -> s.copy(notifications = s.notifications.copy(favourites = v)) }),
            Row.Words("Muted channels", "Never notify for these", "Login name", get = { it.notifications.muted }, set = { s, v -> s.copy(notifications = s.notifications.copy(muted = v)) }),
            Row.Toggle("Category changes", "Notify when a live channel switches game", get = { it.notifications.notifyCategoryChange }, set = { s, v -> s.copy(notifications = s.notifications.copy(notifyCategoryChange = v)) }, enabled = { it.notifications.enabled }),
            Row.Toggle("Quiet hours", get = { it.notifications.quietHoursEnabled }, set = { s, v -> s.copy(notifications = s.notifications.copy(quietHoursEnabled = v)) }, enabled = { it.notifications.enabled }),
            Row.Slider("Quiet from", null, 0f..1410f, 46, ::hhmm, get = { it.notifications.quietStartMinutes.toFloat() }, set = { s, v -> s.copy(notifications = s.notifications.copy(quietStartMinutes = v.toInt())) }, enabled = { it.notifications.quietHoursEnabled }),
            Row.Slider("Quiet until", null, 0f..1410f, 46, ::hhmm, get = { it.notifications.quietEndMinutes.toFloat() }, set = { s, v -> s.copy(notifications = s.notifications.copy(quietEndMinutes = v.toInt())) }, enabled = { it.notifications.quietHoursEnabled }),
            Row.Toggle("Group notifications", get = { it.notifications.groupNotifications }, set = { s, v -> s.copy(notifications = s.notifications.copy(groupNotifications = v)) }),
            Row.Header("Chat pings"),
            Row.Note("When chat isn't on screen", "Notifies while you're on another screen, in picture-in-picture, or outside the app with a stream still running."),
            Row.Toggle("Mentions", "Someone @'s you or replies to you", get = { it.notifications.pingMentions }, set = { s, v -> s.copy(notifications = s.notifications.copy(pingMentions = v)) }),
            Row.Toggle("Highlight keywords", "Set under Filters and highlights", get = { it.notifications.pingKeywords }, set = { s, v -> s.copy(notifications = s.notifications.copy(pingKeywords = v)) }),
            Row.Toggle("Highlighted users", "Set under Filters and highlights", get = { it.notifications.pingHighlightUsers }, set = { s, v -> s.copy(notifications = s.notifications.copy(pingHighlightUsers = v)) }),
            Row.Action("System notification settings", "Sound, vibration and importance", "system_notifications"),
        ),
    )

    val adBlock = Page(
        "adblock", "Ad handling", "Opt-in. Off by default.", Icons.Outlined.Block,
        listOf(
            Row.Note(
                "Experimental and off by default",
                "Twitch stitches ads into the stream itself. These options try to swap in an ad-free copy. They can break whenever Twitch changes things, " +
                    "may lower quality during ad breaks, and are against Twitch's terms. Supporting streamers with a sub or Turbo removes ads properly.",
                warning = true,
            ),
            Row.Toggle("I understand the risks", get = { it.adBlock.acknowledgedRisks }, set = { s, v -> s.copy(adBlock = s.adBlock.copy(acknowledgedRisks = v, enabled = v && s.adBlock.enabled)) }),
            Row.Toggle("Enable ad handling", get = { it.adBlock.enabled }, set = { s, v -> s.copy(adBlock = s.adBlock.copy(enabled = v)) }, enabled = { it.adBlock.acknowledgedRisks }),
            Row.Choice("Method", options = opts(AdMethod.BACKUP_PLAYER_TYPE to "Backup stream (no third party)", AdMethod.PROXY to "Proxy servers you add", AdMethod.BOTH to "Proxy first, then backup"),
                get = { it.adBlock.method.name }, set = { s, v -> s.copy(adBlock = s.adBlock.copy(method = AdMethod.valueOf(v))) }, enabled = { it.adBlock.enabled }),
            Row.MultiChoice("Backup player types", "Tried in order during an ad break", PlayerTypeOption.entries.map { it.name to it.wire },
                get = { it.adBlock.backupPlayerTypes.map(PlayerTypeOption::name) }, set = { s, v -> s.copy(adBlock = s.adBlock.copy(backupPlayerTypes = v.map(PlayerTypeOption::valueOf))) }, ordered = true),
            Row.Toggle("Accept lower quality during ads", get = { it.adBlock.backupLowerQualityOk }, set = { s, v -> s.copy(adBlock = s.adBlock.copy(backupLowerQualityOk = v)) }, enabled = { it.adBlock.enabled }),
            Row.Toggle("Skip ad segments as a last resort", "Video pauses for the ad's length instead of showing it", get = { it.adBlock.stripAdSegmentsAsLastResort }, set = { s, v -> s.copy(adBlock = s.adBlock.copy(stripAdSegmentsAsLastResort = v)) }, enabled = { it.adBlock.enabled }),
            Row.Words(
                "Proxy servers", "Your stream's playlist is fetched through these. Only add servers you trust. Use {channel} where the channel name goes.",
                "https://example.com/live/{channel}.m3u8",
                get = { it.adBlock.proxyTemplates }, set = { s, v -> s.copy(adBlock = s.adBlock.copy(proxyTemplates = v)) }, lowercase = false,
                validate = { if (it.startsWith("https://") && "{channel}" in it) null else "Must start with https:// and contain {channel}" },
            ),
            Row.Toggle("Show 'Ad break' banner", get = { it.adBlock.showAdBreakBanner }, set = { s, v -> s.copy(adBlock = s.adBlock.copy(showAdBreakBanner = v)) }),
            Row.Toggle("Mute during ad breaks", "Works even with ad handling off", get = { it.adBlock.muteDuringAds }, set = { s, v -> s.copy(adBlock = s.adBlock.copy(muteDuringAds = v)) }),
        ),
    )

    val experimental = Page(
        "experimental", "Experimental", "Channel points, multi-view", Icons.Outlined.Science,
        listOf(
            Row.Note("Channel points", "Needs the optional twitch.tv connection under Accounts. Uses Twitch's private API and may stop working without notice."),
            Row.Toggle("Show points balance", get = { it.experimental.showPointsBalance }, set = { s, v -> s.copy(experimental = s.experimental.copy(showPointsBalance = v)) }),
            Row.Toggle("Auto-claim bonus points", "Claims the bonus chest while you watch", get = { it.experimental.autoClaimPoints }, set = { s, v -> s.copy(experimental = s.experimental.copy(autoClaimPoints = v)) }),
            Row.Header("Multi-view"),
            Row.Toggle("Enable multi-view", "Long-press a stream to add it", get = { it.experimental.multiView }, set = { s, v -> s.copy(experimental = s.experimental.copy(multiView = v)) }),
            Row.Slider("Max streams", null, 2f..4f, 1, { it.toInt().toString() }, get = { it.experimental.multiViewMaxStreams.toFloat() }, set = { s, v -> s.copy(experimental = s.experimental.copy(multiViewMaxStreams = v.toInt())) }),
        ),
    )

    val data = Page(
        "data", "Data and storage", "Cache, history, backup, advanced", Icons.Outlined.Storage,
        listOf(
            Row.Toggle("Data saver", "Lower default quality and pause emote animation on mobile data", get = { it.data.dataSaver }, set = { s, v -> s.copy(data = s.data.copy(dataSaver = v)) }),
            Row.Slider("Image cache size", "Applies after restart", 64f..1024f, 14, { "${it.toInt()} MB" }, get = { it.data.imageCacheMb.toFloat() }, set = { s, v -> s.copy(data = s.data.copy(imageCacheMb = v.toInt())) }),
            Row.Action("Clear image cache", null, "clear_cache"),
            Row.Toggle("Keep watch history", get = { it.data.keepWatchHistory }, set = { s, v -> s.copy(data = s.data.copy(keepWatchHistory = v)) }),
            Row.Slider("Keep history for", null, 7f..180f, 0, { "${it.toInt()} days" }, get = { it.data.historyDays.toFloat() }, set = { s, v -> s.copy(data = s.data.copy(historyDays = v.toInt())) }),
            Row.Action("Clear watch history", null, "clear_history", destructive = true),
            Row.Toggle("Keep recent searches", "Channels, categories and searches you opened from Search", get = { it.data.keepRecentSearches }, set = { s, v -> s.copy(data = s.data.copy(keepRecentSearches = v)) }),
            Row.Action("Clear recent searches", null, "clear_recent_searches", destructive = true),
            Row.Header("Backup"),
            Row.Action("Export settings", "Save every setting and filter to a file. Accounts aren't included.", "export"),
            Row.Action("Import settings", "Replace your settings with a saved file", "import"),
            Row.Action("Reset all settings", null, "reset", destructive = true),
            Row.Header("Advanced"),
            Row.Action("Check playback config now", "Fetch the latest Twitch player details from the server", "refresh_config"),
            Row.Toggle("Verbose logging", "Helpful when reporting a bug", get = { it.data.verboseLogging }, set = { s, v -> s.copy(data = s.data.copy(verboseLogging = v)) }),
            Row.Action("View logs", null, "logs"),
        ),
    )

    val about = Page("about", "About", "Version, licences, source", Icons.Outlined.Info, emptyList())

    val pages = listOf(accountPage, general, appearance, player, chat, emotes, filters, notifications, adBlock, experimental, data, about)

    fun page(key: String) = pages.firstOrNull { it.key == key }

    private fun hhmm(m: Float): String = "%02d:%02d".format(m.toInt() / 60, m.toInt() % 60)
    private fun ProviderSlot.label() = when (this) { ProviderSlot.SEVENTV -> "7TV"; ProviderSlot.BTTV -> "BetterTTV"; ProviderSlot.FFZ -> "FrankerFaceZ" }
}
